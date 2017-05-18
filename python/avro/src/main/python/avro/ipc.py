#!/usr/bin/env python3
# -*- mode: python; coding: utf-8 -*-

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""RPC/IPC support."""

import abc
import http.client
import http.server
import io
import logging
import os
import pkgutil
import socketserver
import traceback

from avro import io as avro_io
from avro import protocol
from avro import schema


# ------------------------------------------------------------------------------
# Constants


def load_resource(name):
    """Loads a text resources belonging to the 'avro' package.

    Args:
        name: Name of the resource to load, eg. "HandshakeRequest.avsc".
    Returns:
        Text content the resource contains (UTF-8).
    """
    return pkgutil.get_data("avro", name).decode("utf-8")


HANDSHAKE_REQUEST_SCHEMA_JSON = load_resource("HandshakeRequest.avsc")
HANDSHAKE_RESPONSE_SCHEMA_JSON = load_resource("HandshakeResponse.avsc")

HANDSHAKE_REQUEST_SCHEMA = schema.parse(HANDSHAKE_REQUEST_SCHEMA_JSON)
HANDSHAKE_RESPONSE_SCHEMA = schema.parse(HANDSHAKE_RESPONSE_SCHEMA_JSON)

HANDSHAKE_REQUESTOR_WRITER = avro_io.DatumWriter(HANDSHAKE_REQUEST_SCHEMA)
HANDSHAKE_REQUESTOR_READER = avro_io.DatumReader(HANDSHAKE_RESPONSE_SCHEMA)
HANDSHAKE_RESPONDER_WRITER = avro_io.DatumWriter(HANDSHAKE_RESPONSE_SCHEMA)
HANDSHAKE_RESPONDER_READER = avro_io.DatumReader(HANDSHAKE_REQUEST_SCHEMA)

META_SCHEMA = schema.parse("""{"type": "map", "values": "bytes"}""")
META_WRITER = avro_io.DatumWriter(META_SCHEMA)
META_READER = avro_io.DatumReader(META_SCHEMA)

SYSTEM_ERROR_SCHEMA = schema.parse("""["string"]""")

AVRO_RPC_MIME = "avro/binary"

# protocol cache

# Map: remote name -> remote MD5 hash
_REMOTE_HASHES = {}

# Decoder/encoder for a 32 bits big-endian integer.
UINT32_BE = avro_io.STRUCT_INT

# Default size of the buffers use to frame messages:
BUFFER_SIZE = 8192


# ------------------------------------------------------------------------------
# Exceptions


class AvroRemoteException(schema.AvroException):
    """Raised when an error message is sent by an Avro requestor or responder."""
    pass

class ConnectionClosedException(schema.AvroException):
    pass


# ------------------------------------------------------------------------------
# Base IPC Classes (Requestor/Responder)


class BaseRequestor(object, metaclass=abc.ABCMeta):
    """Base class for the client side of a protocol interaction."""

    def __init__(self, local_protocol, transceiver):
        """Initializes a new requestor object.

        Args:
            local_protocol: Avro Protocol describing the messages sent and received.
            transceiver: Transceiver instance to channel messages through.
        """
        self._local_protocol = local_protocol
        self._transceiver = transceiver
        self._remote_protocol = None
        self._remote_hash = None
        self._send_protocol = None

    @property
    def local_protocol(self):
        """Returns: the Avro Protocol describing the messages sent and received."""
        return self._local_protocol

    @property
    def transceiver(self):
        """Returns: the underlying channel used by this requestor."""
        return self._transceiver

    @abc.abstractmethod
    def _issue_request(self, call_request, message_name, request_datum):
        """TODO: Document this method.

        Args:
            call_request: ???
            message_name: Name of the message.
            request_datum: ???
        Returns:
            ???
        """
        raise Error("Abstract method")

    def request(self, message_name, request_datum):
        """Writes a request message and reads a response or error message.

        Args:
            message_name: Name of the IPC method.
            request_datum: IPC request.
        Returns:
            The IPC response.
        """
        # build handshake and call request
        buffer_writer = io.BytesIO()
        buffer_encoder = avro_io.BinaryEncoder(buffer_writer)
        self._write_handshake_request(buffer_encoder)
        self._write_call_request(message_name, request_datum, buffer_encoder)

        # send the handshake and call request; block until call response
        call_request = buffer_writer.getvalue()
        return self._issue_request(call_request, message_name, request_datum)

    def _write_handshake_request(self, encoder):
        """Emits the handshake request.

        Args:
            encoder: Encoder to write the handshake request into.
        """
        local_hash = self._local_protocol.md5

        # if self._remote_hash is None:
        #   remote_name = self.transceiver.remote_name
        #   self._remote_hash = _REMOTE_HASHES.get(remote_name)

        if self._remote_hash is None:
            self._remote_hash = local_hash
            self._remote_protocol = self._local_protocol

        # HandshakeRequest:
        request_datum = {
            "clientHash": local_hash,
            "clientProtocol": None,
            "serverHash": self._remote_hash,
            "meta": None,
        }
        if self._send_protocol:
            request_datum["clientProtocol"] = str(self._local_protocol)

        logging.debug("Sending handshake request: %s", request_datum)
        HANDSHAKE_REQUESTOR_WRITER.write(request_datum, encoder)

    def _write_call_request(self, message_name, request_datum, encoder):
        """Encodes a call request.

        The format of a call request is:
         - request metadata, as a map with values of type bytes;
         - the message name, as an Avro string;
         - the message parameters, as specified in the protocol message definition.

        Args:
            message_name: Name of the protocol message to encode a request for.
            request_datum: Request parameters.
            encoder: Encoder to encode request with.
        """
        # request metadata (not yet implemented)
        request_metadata = {}
        META_WRITER.write(request_metadata, encoder)

        # Identify message to send:
        message = self.local_protocol.message_map.get(message_name)
        if message is None:
            raise schema.AvroException("Unknown message: %s" % message_name)
        encoder.write_utf8(message.name)

        # message parameters
        self._write_request(message.request, request_datum, encoder)

    def _write_request(self, request_schema, request_datum, encoder):
        logging.debug("Writing request: %s", request_datum)
        datum_writer = avro_io.DatumWriter(request_schema)
        datum_writer.write(request_datum, encoder)

    def _read_handshake_response(self, decoder):
        """Reads and processes the handshake response message.

        Args:
            decoder: Decoder to read messages from.
        Returns:
            call-response exists (boolean) ???
        Raises:
            schema.AvroException on ???
        """
        handshake_response = HANDSHAKE_REQUESTOR_READER.read(decoder)
        logging.debug("Processing handshake response: %s", handshake_response)
        match = handshake_response["match"]
        if match == "BOTH":
            # Both client and server protocol hashes match:
            self._send_protocol = False
            return True

        elif match == "CLIENT":
            # Client side hash mismatch:
            self._remote_protocol = protocol.parse(handshake_response["serverProtocol"])
            self._remote_hash = handshake_response["serverHash"]
            self._send_protocol = False
            return True

        elif match == "NONE":
            # Neither client nor server match:
            self._remote_protocol = protocol.parse(handshake_response["serverProtocol"])
            self._remote_hash = handshake_response["serverHash"]
            self._send_protocol = True
            return False
        else:
            raise schema.AvroException("handshake_response.match=%r" % match)

    def _read_call_response(self, message_name, decoder):
        """Reads and processes a method call response.

        The format of a call response is:
         - response metadata, a map with values of type bytes
         - a one-byte error flag boolean, followed by either:
           - if the error flag is false,
             the message response, serialized per the message's response schema.
           - if the error flag is true,
             the error, serialized per the message's error union schema.

        Args:
            message_name:
            decoder:
        Returns:
            ???
        Raises:
            schema.AvroException on ???
        """
        # response metadata
        response_metadata = META_READER.read(decoder)

        # remote response schema
        remote_message_schema = self._remote_protocol.message_map.get(message_name)
        if remote_message_schema is None:
            raise schema.AvroException("Unknown remote message: %s" % message_name)

        # local response schema
        local_message_schema = self._local_protocol.message_map.get(message_name)
        if local_message_schema is None:
            raise schema.AvroException("Unknown local message: %s" % message_name)

        # error flag
        if not decoder.read_boolean():
            writer_schema = remote_message_schema.response
            reader_schema = local_message_schema.response
            return self._read_response(writer_schema, reader_schema, decoder)
        else:
            writer_schema = remote_message_schema.errors
            reader_schema = local_message_schema.errors
            raise self._read_error(writer_schema, reader_schema, decoder)

    def _read_response(self, writer_schema, reader_schema, decoder):
        datum_reader = avro_io.DatumReader(writer_schema, reader_schema)
        result = datum_reader.read(decoder)
        return result

    def _read_error(self, writer_schema, reader_schema, decoder):
        datum_reader = avro_io.DatumReader(writer_schema, reader_schema)
        return AvroRemoteException(datum_reader.read(decoder))


class Requestor(BaseRequestor):
    """Concrete requestor implementation."""

    def _issue_request(self, call_request, message_name, request_datum):
        call_response = self.transceiver.transceive(call_request)

        # process the handshake and call response
        buffer_decoder = avro_io.BinaryDecoder(io.BytesIO(call_response))
        call_response_exists = self._read_handshake_response(buffer_decoder)
        if call_response_exists:
            return self._read_call_response(message_name, buffer_decoder)
        else:
            return self.request(message_name, request_datum)


# ------------------------------------------------------------------------------


class Responder(object, metaclass=abc.ABCMeta):
    """Base class for the server side of a protocol interaction."""

    def __init__(self, local_protocol):
        self._local_protocol = local_protocol
        self._local_hash = self._local_protocol.md5
        self._protocol_cache = {}

        self.set_protocol_cache(self._local_hash, self._local_protocol)

    @property
    def local_protocol(self):
        return self._local_protocol

    # utility functions to manipulate protocol cache
    def get_protocol_cache(self, hash):
        return self._protocol_cache.get(hash)

    def set_protocol_cache(self, hash, protocol):
        self._protocol_cache[hash] = protocol

    def respond(self, call_request):
        """Entry point to process one procedure call.

        Args:
            call_request: Serialized procedure call request.
                The call request includes:
                 - handshake prefix;
                 - call request metadata (map: string -> bytes);
                 - protocol message name;
                 - encoded request for the protocol message.
        Returns:
            Serialized procedure call response.
        Raises:
            ???
        """
        buffer_reader = io.BytesIO(call_request)
        buffer_decoder = avro_io.BinaryDecoder(buffer_reader)
        buffer_writer = io.BytesIO()
        buffer_encoder = avro_io.BinaryEncoder(buffer_writer)
        error = None

        # Map: string -> bytes
        response_metadata = {}

        try:
            remote_protocol = self._process_handshake(buffer_decoder, buffer_encoder)
            # handshake failure
            if remote_protocol is None:
                return buffer_writer.getvalue()

            # read request using remote protocol
            request_metadata = META_READER.read(buffer_decoder)
            remote_message_name = buffer_decoder.read_utf8()

            # get remote and local request schemas so we can do
            # schema resolution (one fine day)
            remote_message = remote_protocol.message_map.get(remote_message_name)
            if remote_message is None:
                fail_msg = "Unknown remote message: %s" % remote_message_name
                raise schema.AvroException(fail_msg)
            local_message = self.local_protocol.message_map.get(remote_message_name)
            if local_message is None:
                fail_msg = "Unknown local message: %s" % remote_message_name
                raise schema.AvroException(fail_msg)
            writer_schema = remote_message.request
            reader_schema = local_message.request
            request = self._read_request(writer_schema, reader_schema, buffer_decoder)
            logging.debug("Processing request: %r", request)

            # perform server logic
            try:
                response = self.invoke(local_message, request)
            except AvroRemoteException as exn:
                error = exn
            except Exception as exn:
                error = AvroRemoteException(str(exn))

            # write response using local protocol
            META_WRITER.write(response_metadata, buffer_encoder)
            buffer_encoder.write_boolean(error is not None)
            if error is None:
                writer_schema = local_message.response
                self._write_response(writer_schema, response, buffer_encoder)
            else:
                writer_schema = local_message.errors
                self._write_error(writer_schema, error, buffer_encoder)

        except schema.AvroException as exn:
            logging.error("Error while encoding response:\n%s", traceback.format_exc())

            # FIXME: We may already have written the error flag
            error = AvroRemoteException(str(exn))
            buffer_encoder = avro_io.BinaryEncoder(io.BytesIO())
            META_WRITER.write(response_metadata, buffer_encoder)
            buffer_encoder.write_boolean(True)
            self._write_error(SYSTEM_ERROR_SCHEMA, error, buffer_encoder)

        return buffer_writer.getvalue()

    def _process_handshake(self, decoder, encoder):
        """Processes an RPC handshake.

        Args:
            decoder: Where to read from.
            encoder: Where to write to.
        Returns:
            The requested Protocol.
        """
        handshake_request = HANDSHAKE_RESPONDER_READER.read(decoder)
        logging.debug("Processing handshake request: %s", handshake_request)

        # determine the remote protocol
        client_hash = handshake_request.get("clientHash")
        client_protocol = handshake_request.get("clientProtocol")
        remote_protocol = self.get_protocol_cache(client_hash)
        if remote_protocol is None and client_protocol is not None:
            remote_protocol = protocol.parse(client_protocol)
            self.set_protocol_cache(client_hash, remote_protocol)

        # evaluate remote's guess of the local protocol
        server_hash = handshake_request.get("serverHash")

        if remote_protocol is None:
            match = "NONE"
        elif self._local_hash == server_hash:
            match = "BOTH"
        else:
            match = "CLIENT"

        # HandshakeResponse:
        handshake_response = {
            "match": match,
            "serverProtocol": None,
            "serverHash": None,
            "meta": None,
        }

        if match != "BOTH":
            handshake_response["serverProtocol"] = str(self.local_protocol)
            handshake_response["serverHash"] = self._local_hash

        logging.debug("Handshake response: %s", handshake_response)
        HANDSHAKE_RESPONDER_WRITER.write(handshake_response, encoder)
        return remote_protocol

    @abc.abstractmethod
    def invoke(self, local_message, request):
        """Processes one procedure call.

        Args:
            local_message: Avro message specification.
            request: Call request.
        Returns:
            Call response.
        Raises:
            ???
        """
        raise Error("abtract method")

    def _read_request(self, writer_schema, reader_schema, decoder):
        datum_reader = avro_io.DatumReader(writer_schema, reader_schema)
        return datum_reader.read(decoder)

    def _write_response(self, writer_schema, response_datum, encoder):
        datum_writer = avro_io.DatumWriter(writer_schema)
        datum_writer.write(response_datum, encoder)

    def _write_error(self, writer_schema, error_exception, encoder):
        datum_writer = avro_io.DatumWriter(writer_schema)
        datum_writer.write(str(error_exception), encoder)


class AvroResponder(Responder):
    """Generic Avro responder that delegates to an implementation using reflection."""

    def __init__(self, protocol, impl=None):
        super().__init__(local_protocol=protocol)
        self._impl = impl or self

    def invoke(self, message, request, context=None):
        """Processes an incoming message."""
        logging.debug("Processing message %r (%s) with request %r", message.name, message, request)
        try:
            message_handler = getattr(self._impl, message.name)
            return message_handler(
                context=context,
                request=request,
            )
        except:
            logging.error("Error processing message %r:\n%s", message.name, traceback.format_exc())
            raise


# ------------------------------------------------------------------------------
# Framed message


def read_uint32(reader):
    encoded = reader.read(UINT32_BE.size)
    if len(encoded) != UINT32_BE.size:
        raise ConnectionClosedException("Invalid header: %r" % encoded)
    return UINT32_BE.unpack(encoded)[0]


def write_uint32(writer, uint32):
    writer.write(UINT32_BE.pack(uint32))


class FramedReader(object):
    """Wrapper around a file-like object to read framed data."""

    def __init__(self, reader):
        self._reader = reader

    def read(self):
        """Reads one message from the configured reader.

        Returns:
            The message, as bytes.
        """
        message = io.BytesIO()
        # Read and append frames until we encounter a 0-size frame:
        while self._read_frame(message) > 0: pass
        return message.getvalue()

    def _read_frame(self, message):
        """Reads and appends one frame into the given message bytes.

        Args:
            message: Message to append the frame to.
        Returns:
            Size of the frame that was read.
            The empty frame (size 0) indicates the end of a message.
        """
        frame_size = self._read_int32()
        remaining = frame_size
        while remaining > 0:
            data_bytes = self._reader.read(remaining)
            if len(data_bytes) == 0:
                raise ConnectionClosedException(
                    "FramedReader: expecting %d more bytes in frame of size %d, got 0."
                    % (remaining, frame_size))
            message.write(data_bytes)
            remaining -= len(data_bytes)
        return frame_size

    def _read_int32(self):
        return read_uint32(self._reader)


class FramedWriter(object):
    """Wrapper around a file-like object to write framed data."""

    def __init__(self, writer):
        self._writer = writer

    def write(self, message):
        """Writes a message.

        Message is chunked into sequences of frames terminated by an empty frame.

        Args:
            message: Message to write, as bytes.
        """
        while len(message) > 0:
            chunk_size = max(BUFFER_SIZE, len(message))
            chunk = message[:chunk_size]
            self._write_buffer(chunk)
            message = message[chunk_size:]

        # A message is always terminated by a zero-length buffer.
        self._write_uint32(0)

    def _write_buffer(self, chunk):
        self._write_uint32(len(chunk))
        self._writer.write(chunk)

    def _write_uint32(self, uint32):
        write_uint32(self._writer, uint32)


# ------------------------------------------------------------------------------
# Transceiver (send/receive channel)


class Transceiver(object, metaclass=abc.ABCMeta):
    """Interface for transceivers (send/receive channels)."""

    @abc.abstractproperty
    def remote_name(self):
        pass

    @abc.abstractmethod
    def read_message(self):
        """Reads a single message from the channel.

        Blocks until a message can be read.

        Returns:
            The message read from the channel.
        """
        pass

    @abc.abstractmethod
    def write_message(self, message):
        """Writes a message into the channel.

        Blocks until the message has been written.

        Args:
            message: Message to write.
        """
        pass

    def transceive(self, request):
        """Processes a single request-reply interaction.

        Synchronous request-reply interaction.

        Args:
            request: Request message.
        Returns:
            The reply message.
        """
        self.write_message(request)
        result = self.read_message()
        return result

    def close(self):
        """Closes this transceiver."""
        pass


# --------------------------------------------------------------------------------------------------


class HTTPTransceiver(Transceiver):
    """HTTP-based transceiver implementation.

    One full HTTP connection is opened (and closed) per request/response.
    No pipelining or multiplexing allowed.
    """

    def __init__(self, host, port, req_resource="/"):
        """Initializes a new HTTP transceiver.

        Args:
            host: Name or IP address of the remote host to interact with.
            port: Port the remote server is listening on.
            req_resource: Optional HTTP resource path to use, "/" by default.
        """
        self._req_resource = req_resource
        self._conn = http.client.HTTPConnection(host, port)

    @property
    def remote_name(self):
        if self._conn.sock is not None:
            return self._conn.sock.getsockname()
        return None

    def read_message(self):
        response = self._conn.getresponse()
        response_reader = FramedReader(response)
        framed_message = response_reader.read()
        response.read()    # ensure we're ready for subsequent requests
        return framed_message

    def write_message(self, message):
        req_method = "POST"
        req_headers = {"Content-Type": AVRO_RPC_MIME}

        bio = io.BytesIO()
        req_body_buffer = FramedWriter(bio)
        req_body_buffer.write(message)
        req_body = bio.getvalue()

        self._conn.request(
            method=req_method,
            url=self._req_resource,
            body=req_body,
            headers=req_headers,
        )

    def close(self):
        self._conn.close()
        self._conn = None


# ------------------------------------------------------------------------------
# Server Implementations


def _make_handler_class(responder):
    class AvroHTTPRequestHandler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            reader = FramedReader(self.rfile)
            call_request = reader.read()
            logging.debug("Serialized request: %r", call_request)
            call_response = responder.respond(call_request)
            logging.debug("Serialized response: %r", call_response)

            self.send_response(200)
            self.send_header("Content-type", AVRO_RPC_MIME)
            self.end_headers()

            framed_writer = FramedWriter(self.wfile)
            framed_writer.write(call_response)
            self.wfile.flush()
            logging.debug("Response sent (flushed)")

        def log_request(self, *args, **kwargs):
            """Disable HTTP logging."""
            pass

    return AvroHTTPRequestHandler


class MultiThreadedHTTPServer(
        socketserver.ThreadingMixIn,
        http.server.HTTPServer,
):
    """Multi-threaded HTTP server."""
    pass


class AvroIpcHttpServer(MultiThreadedHTTPServer):
    """Avro IPC server implemented on top of an HTTP server."""

    def __init__(self, interface, port, responder):
        """Initializes a new Avro IPC server.

        Args:
            interface: Interface the server listens on, eg. "localhost" or "0.0.0.0".
            port: TCP port the server listens on, eg. 8000.
            responder: Responder implementation to handle RPCs.
        """
        super().__init__(
            server_address=(interface, port),
            RequestHandlerClass=_make_handler_class(responder),
        )


if __name__ == "__main__":
    raise Exception("Not a standalone module")