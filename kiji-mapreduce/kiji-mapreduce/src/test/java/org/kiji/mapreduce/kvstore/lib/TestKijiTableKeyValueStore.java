/**
 * (c) Copyright 2012 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.mapreduce.kvstore.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.kiji.schema.util.ResourceUtils.closeOrLog;
import static org.kiji.schema.util.ResourceUtils.releaseOrLog;

import java.io.IOException;

import org.apache.avro.Schema;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.ReflectionUtils;
import org.junit.Before;
import org.junit.Test;

import org.kiji.mapreduce.kvstore.KeyValueStoreReader;
import org.kiji.mapreduce.kvstore.framework.KeyValueStoreConfiguration;
import org.kiji.mapreduce.kvstore.impl.KeyValueStoreConfigSerializer;
import org.kiji.schema.KijiClientTest;
import org.kiji.schema.KijiRowKeyComponents;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableWriter;
import org.kiji.schema.KijiURI;
import org.kiji.schema.layout.KijiTableLayouts;

public class TestKijiTableKeyValueStore extends KijiClientTest {
  @Before
  public void setupEnvironment() throws Exception {
    getKiji().createTable(KijiTableLayouts.getLayout(KijiTableLayouts.SIMPLE));
  }

  /** @return an uninitialized store to test for initialization from a Configuration. */
  @SuppressWarnings("unchecked")
  private <T> KijiTableKeyValueStore<T> getUninitializedStore() {
    return (KijiTableKeyValueStore<T>) ReflectionUtils.newInstance(
        KijiTableKeyValueStore.class, new Configuration());
  }

  @Test
  public void testSerialization() throws IOException {
    // Test that we can serialize a KijiTableKeyValueStore to a conf and resurrect it.
    KijiTableKeyValueStore<String> input = KijiTableKeyValueStore.builder()
        .withTable(KijiURI.newBuilder(getKiji().getURI().toString() + "/table").build())
        .withColumn("some", "column")
        .withMinTimestamp(42)
        .withMaxTimestamp(512)
        .withCacheLimit(2121)
        .withReaderSchema(Schema.create(Schema.Type.STRING))
        .build();

    KeyValueStoreConfiguration conf = KeyValueStoreConfiguration.fromConf(new Configuration());

    input.storeToConf(conf);
    conf.getDelegate().set(KeyValueStoreConfiguration.KEY_VALUE_STORE_NAMESPACE
        + ".0." + KeyValueStoreConfigSerializer.CONF_NAME, "the-store-name");

    KijiTableKeyValueStore<String> output = getUninitializedStore();
    output.initFromConf(conf);

    assertEquals(input, output);
  }

  @Test
  public void testOkWithoutSchema() throws IOException {
    // Serializing without an explicit reader schema is ok.
    KijiTableKeyValueStore<String> input = KijiTableKeyValueStore.builder()
        .withTable(KijiURI.newBuilder(getKiji().getURI().toString() + "/table").build())
        .withColumn("some", "column")
        .withMinTimestamp(42)
        .withMaxTimestamp(512)
        .withCacheLimit(2121)
        .build();

    KeyValueStoreConfiguration conf = KeyValueStoreConfiguration.fromConf(
        new Configuration(false));

    input.storeToConf(conf);
    conf.getDelegate().set(KeyValueStoreConfiguration.KEY_VALUE_STORE_NAMESPACE
        + ".0." + KeyValueStoreConfigSerializer.CONF_NAME, "the-store-name");

    KijiTableKeyValueStore<String> output = getUninitializedStore();
    output.initFromConf(conf);

    assertEquals(input, output);
  }

  @Test
  public void testRequiresRealTable() throws IOException {
    final KijiURI tableURI =
        KijiURI.newBuilder(getKiji().getURI().toString() + "/table_name_not_real").build();
    try {
      // The specified table must exist in the Kiji instance.

      KijiTableKeyValueStore<String> input = KijiTableKeyValueStore.builder()
        .withTable(tableURI)
        .withColumn("some", "column")
        .withMinTimestamp(42)
        .withMaxTimestamp(512)
        .withCacheLimit(2121)
        .build();
      fail("Should have thrown an IllegalArgumentException.");
    } catch (IllegalArgumentException iae) {
      assertEquals("Could not open table: " + tableURI, iae.getMessage());
    }
  }

  @Test
  public void testRequiresTableUri() {
    try {
      // Test that we need to set the table URI, or it will fail to verify as input.
      KijiTableKeyValueStore<String> input = KijiTableKeyValueStore.builder()
        .withColumn("some", "column")
        .withMinTimestamp(42)
        .withMaxTimestamp(512)
        .withCacheLimit(2121)
        .build();
      fail("Should have thrown an IllegalArgumentException.");
    } catch (IllegalArgumentException iae) {
      assertEquals("Must specify non-null table URI", iae.getMessage());
    }
  }

  @Test
  public void testRequiresTableNameInUri() throws IOException {
    try {
      // Test that we need to set the table URI to have both an instance
      // name and a table name, or it will fail to verify as input.
      KijiTableKeyValueStore<String> input = KijiTableKeyValueStore.builder()
        .withTable(getKiji().getURI())
        .withColumn("some", "column")
        .withMinTimestamp(42)
        .withMaxTimestamp(512)
        .withCacheLimit(2121)
        .build();
      fail("Should have thrown an IllegalArgumentException.");
    } catch (IllegalArgumentException iae) {
      assertEquals("Must specify a non-empty table name", iae.getMessage());
    }
  }

  @Test
  public void testRequiresColumn() throws IOException {
    try {
      // Test that we need to set the column to read.
      KijiTableKeyValueStore<String> input = KijiTableKeyValueStore.builder()
        .withTable(
            KijiURI.newBuilder(getKiji().getURI().toString()).withTableName("table").build())
        .withMinTimestamp(42)
        .withMaxTimestamp(512)
        .withCacheLimit(2121)
        .build();
      fail("Should have thrown an IllegalArgumentException.");
    } catch (IllegalArgumentException iae) {
      assertEquals("Must specify a fully-qualified column", iae.getMessage());
    }
  }

  @Test
  public void testSuccessfulReadingFromKVStore() throws IOException {
    KijiTable table = getKiji().openTable("table");
    try {
      KijiRowKeyComponents rowKey = KijiRowKeyComponents.fromComponents("identifier");
      String value = "value";
      KijiTableWriter writer = table.openTableWriter();
      try {
        writer.put(rowKey.getEntityIdForTable(table), "family", "column", "value");
      } finally {
        closeOrLog(writer);
      }

      KijiTableKeyValueStore<CharSequence> input = KijiTableKeyValueStore.builder()
          .withTable(KijiURI.newBuilder(getKiji().getURI().toString() + "/table").build())
          .withColumn("family", "column")
          .build();
      KeyValueStoreReader<KijiRowKeyComponents, CharSequence> reader = input.open();
      try {
        assertTrue(reader.containsKey(rowKey));
        assertEquals(value, reader.get(rowKey).toString());
        assertFalse(reader.containsKey(KijiRowKeyComponents.fromComponents("missingIdentifier")));
      } finally {
        reader.close();
      }
    } finally {
      releaseOrLog(table);
    }
  }
}
