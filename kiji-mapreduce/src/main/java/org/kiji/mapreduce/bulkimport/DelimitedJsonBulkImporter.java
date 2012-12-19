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

package org.kiji.mapreduce.bulkimport;

import java.io.IOException;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.hadoop.configurator.HadoopConf;
import org.kiji.hadoop.configurator.HadoopConfigurator;
import org.kiji.mapreduce.KijiTableContext;
import org.kiji.schema.EntityId;

/**
 * <p>Bulk imports data from json-formatted delimited files to fully qualified kiji cells.
 * By default, records are separated by a tab.  This can be set to a custom character
 * by setting the "kiji.import.text.column.separator" Configuration variable.</p>
 *
 * <p>In general, all records in this file must be formatted as json.
 * For convenience, however, strings may be represented without quotes.
 * For example, both <i>"my-var"</i> and <i>my-var</i> are acceptable representations
 * for the String "my-var".
 *
 * <p>This implementation uses the first entry on each line as the entity-id for
 * each datum following it.  Thus, it would be natural to omit this first entry
 * from being imported, since it is already used as the EntityId.
 * Override the <code>getEntityId()</code> method to specify a custom way of
 * generating EntityIds from a line of data.</p>
 *
 * @see org.kiji.mapreduce.bulkimport.TextInputDescriptorParser
 *
 * {@inheritDoc}
 */
public class DelimitedJsonBulkImporter extends DescribedInputTextBulkImporter {
  private static final Logger LOG = LoggerFactory.getLogger(DelimitedJsonBulkImporter.class);

  /** Configuration variable that specifies the column value separator in the text input files. */
  public static final String CONF_COLUMN_DELIMITER = "kiji.import.text.column.separator";

  /** Configuration variable that specifies the cell value separator in the text input files. */
  public static final String CONF_CELL_DELIMITER = "kiji.import.text.cell.separator";

  /** The string that separates the columns of data in the input file. */
  @HadoopConf(key=CONF_COLUMN_DELIMITER)
  private String mColumnDelimiter = "\t";

  /** The string that separates the qualifiers in the data within a single cell. */
  @HadoopConf(key=CONF_CELL_DELIMITER)
  private String mCellDelimiter = "|";

  /**
   * Exception thrown if a line can not be parsed to match the Schemas
   * described by DescribedInputTextBulkImporter.getColumnInfo().
   */
  protected static class MalformedLineException extends Exception {
    /**
     * Constructor.
     *
     * @param message A message explaining this exception.
     */
    public MalformedLineException(String message) {
      super(message);
    }

    /**
     * Constructor.
     *
     * @param cause The specific cause.
     */
    public MalformedLineException(Throwable cause) {
      super(cause);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void setConf(Configuration conf) {
    super.setConf(conf);
    HadoopConfigurator.configure(this);
  }

  /**
   * <p>Splits each line on the delimiter returned by <code>getColumnDelimiter()</code>,
   * and writes each element to the matching column specified by
   * <code>getColumnInfo().getKijiColumnName()</code>
   * using the matching writer schema specified by <code>getColumnInfo().getSchema()</code>.
   * This method returns the EnitityId generated by <code>getEntityId()</code>.</p>
   *
   * <p>If a line is malformed, or can not be parsed into the schema specified
   * by <code>getColumnInfo().getSchema()</code>, then this error is logged, and
   * the line skipped.  No writes will be made for this line, but the map task will continue.</p>
   *
   * {@inheritDoc}
   */
  @Override
  public void produce(Text line, KijiTableContext context)
      throws IOException {
    try {
      String[] cells = readColumnData(line.toString());
      EntityId entity = getEntityId(cells, context);
      for (int i = 0; i < getColumnInfo().size(); i++) {
        if (getColumnInfo(i).isEmpty()) {
          continue;
        }

        final String family = getColumnInfo(i).getKijiColumnName().getFamily();
        final String qualifier = getColumnInfo(i).getKijiColumnName().getQualifier();
        context.put(entity, family, qualifier, cells[i]);
      }
    } catch (MalformedLineException mle) {
      // Log error without failing the map task or writing this line.
      // TODO(WIBI-1655): Log this in a counter on the context.
      LOG.warn(mle.getMessage());
    }

    // TODO(WIBI-1654): Reinstate map-type bulk imports
    // (possibly in a different class?).
  }

  /**
   * Generates the entity id for this imported line.
   * Called within the produce() method.
   * This implementation returns the first entry in <code>entries</code> as the EntityId.
   * Override this method to specify a different EntityId during the produce() method.
   *
   * @param entries One line of input text split on the column delimiter.
   * @param context The context used by the produce() method.
   * @return The EntityId for the data that gets imported by this line.
   */
  protected EntityId getEntityId(String[] entries, KijiTableContext context) {
    return context.getEntityId(entries[0]);
  }

  /**
   * Parse the line into separate data for each column family.
   *
   * @param line The line of input text from the file.
   * @return A list of column data.
   * @throws MalformedLineException if this line does not split to the same number
   *     of elements as <code>getColumnInfo().size()</code>.
   */
  private String[] readColumnData(String line) throws MalformedLineException {
    String[] columnData = StringUtils.splitPreserveAllTokens(line, getColumnDelimiter());
    if (columnData.length != getColumnInfo().size()) {
      // Line is malformed.  Log error, and throw a handlable exception so
      // that the produce() method can skip this line.
      String message = "Row with incorrect number of columns: " + line
          + "  Expected " + getColumnInfo().size() + " but was " + columnData.length + ".";
      LOG.warn(message);
      throw new MalformedLineException(message);
    }
    return columnData;
  }

  /**
   * Returns the token used to separate column entries.
   *
   * @return The token used to separate column entries.
   */
  protected String getColumnDelimiter() {
    return mColumnDelimiter;
  }

  /**
   * Returns the token used to separate cell entries within a column (for map types).
   *
   * @return the token used to separate cell entries within a column (for map types).
   * @deprecated This class does not support map-type imports.
   */
  @Deprecated
  protected String getCellDelimiter() {
    return mCellDelimiter;
  }
}
