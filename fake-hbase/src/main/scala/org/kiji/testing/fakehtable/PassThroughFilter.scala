package org.kiji.testing.fakehtable

import org.apache.hadoop.hbase.filter.Filter
import org.apache.hadoop.hbase.filter.FilterBase
import org.apache.hadoop.hbase.Cell
import org.apache.hadoop.hbase.filter.Filter.ReturnCode
/** Pass-through HBase filter, ie. behaves as if there were no filter. */
object PassThroughFilter extends FilterBase {
  override def filterKeyValue(cell:Cell) = ReturnCode.SKIP
  def parseFrom(bytes: Array[Byte]): Filter = {

    this
  }
}
