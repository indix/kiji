/**
 * (c) Copyright 2013 WibiData, Inc.
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

package org.kiji.mapreduce.lib.bulkimport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Counters;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.kiji.mapreduce.KijiMRTestLayouts;
import org.kiji.mapreduce.KijiMapReduceJob;
import org.kiji.mapreduce.TestingResources;
import org.kiji.mapreduce.bulkimport.KijiBulkImportJobBuilder;
import org.kiji.mapreduce.framework.JobHistoryCounters;
import org.kiji.mapreduce.framework.KijiConfKeys;
import org.kiji.mapreduce.input.MapReduceJobInputs;
import org.kiji.mapreduce.output.MapReduceJobOutputs;
import org.kiji.schema.KijiClientTest;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiRowData;
import org.kiji.schema.KijiRowScanner;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableReader;
import org.kiji.schema.layout.KijiTableLayout;
import org.kiji.schema.util.InstanceBuilder;

/** Unit tests. */
public class TestCSVBulkImporter extends KijiClientTest {
  private KijiTable mTable;
  private KijiTableReader mReader;

  @Before
  public final void setupTestBulkImporter() throws Exception {
    // Get the test table layouts.
    final KijiTableLayout layout =
        KijiTableLayout.newLayout(KijiMRTestLayouts.getTestLayout());

    // Populate the environment.
    new InstanceBuilder(getKiji())
        .withTable("test", layout)
        .build();

    // Fill local variables.
    mTable = getKiji().openTable("test");
    mReader = mTable.openTableReader();
  }

  @After
  public final void teardownTestBulkImporter() throws Exception {
    mReader.close();
    mTable.release();
  }

  @Test
  public void testCSVBulkImporter() throws Exception {
    // Prepare input file:
    final File inputFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.CSV_IMPORT_DATA,
        getLocalTempDir()
    );

    // Prepare descriptor file:
    final File descriptorFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.FOO_IMPORT_DESCRIPTOR,
        getLocalTempDir()
    );

    final Configuration conf = getConf();
    conf.set(DescribedInputTextBulkImporter.CONF_FILE, descriptorFile.getCanonicalPath());

    // Run the bulk-import:
    final KijiMapReduceJob job = KijiBulkImportJobBuilder.create()
        .withConf(conf)
        .withBulkImporter(CSVBulkImporter.class)
        .withInput(MapReduceJobInputs.newTextMapReduceJobInput(new Path(inputFile.toString())))
        .withOutput(MapReduceJobOutputs.newDirectKijiTableMapReduceJobOutput(mTable.getURI()))
        .build();
    assertTrue(job.run());

    final Counters counters = job.getHadoopJob().getCounters();
    assertEquals(4,
        counters.findCounter(JobHistoryCounters.BULKIMPORTER_RECORDS_PROCESSED).getValue());

    // Validate output:
    final KijiRowScanner scanner = mReader.getScanner(KijiDataRequest.create("info"));
    BulkImporterTestUtils.validateImportedRows(scanner, false);
    scanner.close();
  }

  @Test
  public void testInjectedHeaderRow() throws Exception {
    final String headerRow = "first,last,email,phone";

    // Prepare input file:
    final File inputFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.HEADERLESS_CSV_IMPORT_DATA,
        getLocalTempDir()
    );

    // Prepare descriptor file:
    final File descriptorFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.FOO_IMPORT_DESCRIPTOR,
        getLocalTempDir()
    );

    final Configuration conf = getConf();
    conf.set(DescribedInputTextBulkImporter.CONF_FILE, descriptorFile.getCanonicalPath());

    // Set the header row
    conf.set(CSVBulkImporter.CONF_INPUT_HEADER_ROW, headerRow);

    // Run the bulk-import:
    final KijiMapReduceJob job = KijiBulkImportJobBuilder.create()
        .withConf(conf)
        .withBulkImporter(CSVBulkImporter.class)
        .withInput(MapReduceJobInputs.newTextMapReduceJobInput(new Path(inputFile.toString())))
        .withOutput(MapReduceJobOutputs.newDirectKijiTableMapReduceJobOutput(mTable.getURI()))
        .build();
    assertTrue(job.run());

    final Counters counters = job.getHadoopJob().getCounters();
    assertEquals(3,
        counters.findCounter(JobHistoryCounters.BULKIMPORTER_RECORDS_PROCESSED).getValue());
    assertEquals(1,
        counters.findCounter(JobHistoryCounters.BULKIMPORTER_RECORDS_INCOMPLETE).getValue());
    assertEquals(0,
        counters.findCounter(JobHistoryCounters.BULKIMPORTER_RECORDS_REJECTED).getValue());

    // Validate output:
    final KijiRowScanner scanner = mReader.getScanner(KijiDataRequest.create("info"));
    BulkImporterTestUtils.validateImportedRows(scanner, false);
    scanner.close();
  }

  @Test
  public void testTimestampedCSVBulkImporter() throws Exception {
    // Prepare input file:
    final File inputFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.TIMESTAMP_CSV_IMPORT_DATA,
        getLocalTempDir()
    );

    // Prepare descriptor file:
    final File descriptorFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.FOO_TIMESTAMP_IMPORT_DESCRIPTOR,
        getLocalTempDir()
    );

    final Configuration conf = getConf();
    conf.set(DescribedInputTextBulkImporter.CONF_FILE, descriptorFile.getCanonicalPath());

    // Run the bulk-import:
    final KijiMapReduceJob job = KijiBulkImportJobBuilder.create()
        .withConf(conf)
        .withBulkImporter(CSVBulkImporter.class)
        .withInput(MapReduceJobInputs.newTextMapReduceJobInput(new Path(inputFile.toString())))
        .withOutput(MapReduceJobOutputs.newDirectKijiTableMapReduceJobOutput(mTable.getURI()))
        .build();
    assertTrue(job.run());

    final Counters counters = job.getHadoopJob().getCounters();
    assertEquals(4,
        counters.findCounter(JobHistoryCounters.BULKIMPORTER_RECORDS_PROCESSED).getValue());

    // Validate output:
    final KijiRowScanner scanner = mReader.getScanner(KijiDataRequest.create("info"));
    BulkImporterTestUtils.validateImportedRows(scanner, true);
    scanner.close();
  }

  @Test
  public void testTSVBulkImporter() throws Exception {
    // Prepare input file:
    final File inputFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.TSV_IMPORT_DATA,
        getLocalTempDir()
    );

    // Prepare descriptor file:
    final File descriptorFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.FOO_IMPORT_DESCRIPTOR,
        getLocalTempDir()
    );

    final Configuration conf = getConf();
    conf.set(DescribedInputTextBulkImporter.CONF_FILE, descriptorFile.getCanonicalPath());
    conf.set(CSVBulkImporter.CONF_FIELD_DELIMITER, "\t");

    // Run the bulk-import:
    final KijiMapReduceJob job = KijiBulkImportJobBuilder.create()
        .withConf(conf)
        .withBulkImporter(CSVBulkImporter.class)
        .withInput(MapReduceJobInputs.newTextMapReduceJobInput(new Path(inputFile.toString())))
        .withOutput(MapReduceJobOutputs.newDirectKijiTableMapReduceJobOutput(mTable.getURI()))
        .build();
    assertTrue(job.run());

    final Counters counters = job.getHadoopJob().getCounters();
    assertEquals(4,
        counters.findCounter(JobHistoryCounters.BULKIMPORTER_RECORDS_PROCESSED).getValue());
    assertEquals(1,
        counters.findCounter(JobHistoryCounters.BULKIMPORTER_RECORDS_INCOMPLETE).getValue());
    assertEquals(0,
        counters.findCounter(JobHistoryCounters.BULKIMPORTER_RECORDS_REJECTED).getValue());

    // Validate output:
    final KijiRowScanner scanner = mReader.getScanner(KijiDataRequest.create("info"));
    BulkImporterTestUtils.validateImportedRows(scanner, false);
    scanner.close();
  }

  @Test
  public void testFailOnInvalidDelimiter() throws Exception {
    // Prepare input file:
    final File inputFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.CSV_IMPORT_DATA,
        getLocalTempDir()
    );

    // Prepare descriptor file:
    final File descriptorFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.FOO_IMPORT_DESCRIPTOR,
        getLocalTempDir()
    );

    final Configuration conf = getConf();
    conf.set(DescribedInputTextBulkImporter.CONF_FILE, descriptorFile.getCanonicalPath());
    conf.set(CSVBulkImporter.CONF_FIELD_DELIMITER, "!");
    conf.set(KijiConfKeys.KIJI_OUTPUT_TABLE_URI, mTable.getURI().toString());
    CSVBulkImporter csvbi = new CSVBulkImporter();
    csvbi.setConf(conf);
    try {
      csvbi.setup(null);
      fail("Should've gotten an IOException by here.");
    } catch (IOException ie) {
      assertEquals("Invalid delimiter '!' specified.  Valid options are: ',','\t'",
          ie.getMessage());
    }
  }

  @Test
  public void testPrimitives() throws Exception {
    // Prepare input file:
    final File inputFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.PRIMITIVE_IMPORT_DATA,
        getLocalTempDir()
    );

    // Prepare descriptor file:
    final File descriptorFile = TestingResources.getResourceAsTempFile(
        BulkImporterTestUtils.FOO_PRIMITIVE_IMPORT_DESCRIPTOR,
        getLocalTempDir()
    );

    final Configuration conf = getConf();
    conf.set(DescribedInputTextBulkImporter.CONF_FILE, descriptorFile.getCanonicalPath());

    // Run the bulk-import:
    final KijiMapReduceJob job = KijiBulkImportJobBuilder.create()
        .withConf(conf)
        .withBulkImporter(CSVBulkImporter.class)
        .withInput(MapReduceJobInputs.newTextMapReduceJobInput(new Path(inputFile.toString())))
        .withOutput(MapReduceJobOutputs.newDirectKijiTableMapReduceJobOutput(mTable.getURI()))
        .build();
    assertTrue(job.run());

    final Counters counters = job.getHadoopJob().getCounters();
    assertEquals(2,
        counters.findCounter(JobHistoryCounters.BULKIMPORTER_RECORDS_PROCESSED).getValue());

    // Validate output:
    final KijiRowScanner scanner = mReader.getScanner(KijiDataRequest.create("primitives"));
    KijiRowData row = scanner.iterator().next();
    assertEquals(false, row.getMostRecentValue("primitives", "boolean"));
    assertEquals(Integer.valueOf(0), row.getMostRecentValue("primitives", "int"));
    assertEquals(Long.valueOf(1L), row.getMostRecentValue("primitives", "long"));
    assertEquals(Float.valueOf(1.0f), row.getMostRecentValue("primitives", "float"));
    assertEquals(Double.valueOf(2.0d), row.getMostRecentValue("primitives", "double"));
    assertEquals("Hello", row.getMostRecentValue("primitives", "string").toString());
    scanner.close();
  }
}
