/*
 * Copyright 2009 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.client;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.io.TimeRange;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableFactories;

/**
 * Used to perform Scan operations.
 * <p>
 * All operations are identical to {@link Get} with the exception of
 * instantiation.  Rather than specifying a single row, an optional startRow
 * and stopRow may be defined.  If rows are not specified, the Scanner will
 * iterate over all rows.
 * <p>
 * To scan everything for each row, instantiate a Scan object.
 * <p>
 * To modify scanner caching for just this scan, use {@link #setCaching(int) setCaching}.
 * <p>
 * To further define the scope of what to get when scanning, perform additional 
 * methods as outlined below.
 * <p>
 * To get all columns from specific families, execute {@link #addFamily(byte[]) addFamily}
 * for each family to retrieve.
 * <p>
 * To get specific columns, execute {@link #addColumn(byte[], byte[]) addColumn}
 * for each column to retrieve.
 * <p>
 * To only retrieve columns within a specific range of version timestamps,
 * execute {@link #setTimeRange(long, long) setTimeRange}.
 * <p>
 * To only retrieve columns with a specific timestamp, execute
 * {@link #setTimeStamp(long) setTimestamp}.
 * <p>
 * To limit the number of versions of each column to be returned, execute
 * {@link #setMaxVersions(int) setMaxVersions}.
 * <p>
 * To limit the maximum number of values returned for each call to next(),
 * execute {@link #setBatch(int) setBatch}.
 * <p>
 * To add a filter, execute {@link #setFilter(org.apache.hadoop.hbase.filter.Filter) setFilter}.
 * <p>
 * Expert: To explicitly disable server-side block caching for this scan, 
 * execute {@link #setCacheBlocks(boolean)}.
 */
public class Scan implements Writable {
  private static final byte SCAN_VERSION = (byte)1;

  private byte [] startRow = HConstants.EMPTY_START_ROW;
  private byte [] stopRow  = HConstants.EMPTY_END_ROW;
  private int maxVersions = 1;
  private int batch = -1;
  private int caching = -1;
  private boolean cacheBlocks = true;
  private Filter filter = null;
  private TimeRange tr = new TimeRange();
  private Map<byte [], NavigableSet<byte []>> familyMap =
    new TreeMap<byte [], NavigableSet<byte []>>(Bytes.BYTES_COMPARATOR);
  
  /**
   * Create a Scan operation across all rows.
   */
  public Scan() {}

  public Scan(byte [] startRow, Filter filter) {
    this(startRow);
    this.filter = filter;
  }
  
  /**
   * Create a Scan operation starting at the specified row.
   * <p>
   * If the specified row does not exist, the Scanner will start from the
   * next closest row after the specified row.
   * @param startRow row to start scanner at or after
   */
  public Scan(byte [] startRow) {
    this.startRow = startRow;
  }
  
  /**
   * Create a Scan operation for the range of rows specified.
   * @param startRow row to start scanner at or after (inclusive)
   * @param stopRow row to stop scanner before (exclusive)
   */
  public Scan(byte [] startRow, byte [] stopRow) {
    this.startRow = startRow;
    this.stopRow = stopRow;
  }
  
  /**
   * Creates a new instance of this class while copying all values.
   * 
   * @param scan  The scan instance to copy from.
   * @throws IOException When copying the values fails.
   */
  public Scan(Scan scan) throws IOException {
    startRow = scan.getStartRow();
    stopRow  = scan.getStopRow();
    maxVersions = scan.getMaxVersions();
    batch = scan.getBatch();
    caching = scan.getCaching();
    cacheBlocks = scan.getCacheBlocks();
    filter = scan.getFilter(); // clone?
    TimeRange ctr = scan.getTimeRange();
    tr = new TimeRange(ctr.getMin(), ctr.getMax());
    Map<byte[], NavigableSet<byte[]>> fams = scan.getFamilyMap();
    for (byte[] fam : fams.keySet()) {
      NavigableSet<byte[]> cols = fams.get(fam);
      if (cols != null && cols.size() > 0) {
        for (byte[] col : cols) {
          addColumn(fam, col);
        }
      } else {
        addFamily(fam);
      }
    }
  }

  /**
   * Get all columns from the specified family.
   * <p>
   * Overrides previous calls to addColumn for this family.
   * @param family family name
   */
  public Scan addFamily(byte [] family) {
    familyMap.remove(family);
    familyMap.put(family, null);
    return this;
  }
  
  /**
   * Get the column from the specified family with the specified qualifier.
   * <p>
   * Overrides previous calls to addFamily for this family.
   * @param family family name
   * @param qualifier column qualifier
   */
  public Scan addColumn(byte [] family, byte [] qualifier) {
    NavigableSet<byte []> set = familyMap.get(family);
    if(set == null) {
      set = new TreeSet<byte []>(Bytes.BYTES_COMPARATOR);
    }
    set.add(qualifier);
    familyMap.put(family, set);

    return this;
  }
  
  /**
   * Get versions of columns only within the specified timestamp range,
   * [minStamp, maxStamp).
   * @param minStamp minimum timestamp value, inclusive
   * @param maxStamp maximum timestamp value, exclusive
   * @throws IOException if invalid time range
   */
  public Scan setTimeRange(long minStamp, long maxStamp)
  throws IOException {
    tr = new TimeRange(minStamp, maxStamp);
    return this;
  }
  
  /**
   * Get versions of columns with the specified timestamp.
   * @param timestamp version timestamp  
   */
  public Scan setTimeStamp(long timestamp) {
    try {
      tr = new TimeRange(timestamp, timestamp+1);
    } catch(IOException e) {
      // Will never happen
    }
    return this;
  }

  /**
   * Set the start row.
   * @param startRow
   */
  public Scan setStartRow(byte [] startRow) {
    this.startRow = startRow;
    return this;
  }
  
  /**
   * Set the stop row.
   * @param stopRow
   */
  public Scan setStopRow(byte [] stopRow) {
    this.stopRow = stopRow;
    return this;
  }
  
  /**
   * Get all available versions.
   */
  public Scan setMaxVersions() {
    this.maxVersions = Integer.MAX_VALUE;
    return this;
  }

  /**
   * Get up to the specified number of versions of each column.
   * @param maxVersions maximum versions for each column
   */
  public Scan setMaxVersions(int maxVersions) {
    this.maxVersions = maxVersions;
    return this;
  }

  /**
   * Set the maximum number of values to return for each call to next()
   * @param batch the maximum number of values
   */
  public void setBatch(int batch) {
    this.batch = batch;
  }

  /**
   * Set the number of rows for caching that will be passed to scanners.
   * If not set, the default setting from {@link HTable#getScannerCaching()} will apply.
   * Higher caching values will enable faster scanners but will use more memory.
   * @param caching the number of rows for caching
   */
  public void setCaching(int caching) {
    this.caching = caching;
  }

  /**
   * Apply the specified server-side filter when performing the Scan.
   * @param filter filter to run on the server
   */
  public Scan setFilter(Filter filter) {
    this.filter = filter;
    return this;
  }

  /**
   * Setting the familyMap
   * @param familyMap
   */
  public Scan setFamilyMap(Map<byte [], NavigableSet<byte []>> familyMap) {
    this.familyMap = familyMap;
    return this;
  }
  
  /**
   * Getting the familyMap
   * @return familyMap
   */
  public Map<byte [], NavigableSet<byte []>> getFamilyMap() {
    return this.familyMap;
  }
  
  /**
   * @return the number of families in familyMap
   */
  public int numFamilies() {
    if(hasFamilies()) {
      return this.familyMap.size();
    }
    return 0;
  }

  /**
   * @return true if familyMap is non empty, false otherwise
   */
  public boolean hasFamilies() {
    return !this.familyMap.isEmpty();
  }
  
  /**
   * @return the keys of the familyMap
   */
  public byte[][] getFamilies() {
    if(hasFamilies()) {
      return this.familyMap.keySet().toArray(new byte[0][0]);
    }
    return null;
  }
  
  /**
   * @return the startrow
   */
  public byte [] getStartRow() {
    return this.startRow;
  }

  /**
   * @return the stoprow
   */
  public byte [] getStopRow() {
    return this.stopRow;
  }
  
  /**
   * @return the max number of versions to fetch
   */
  public int getMaxVersions() {
    return this.maxVersions;
  } 

  /**
   * @return maximum number of values to return for a single call to next()
   */
  public int getBatch() {
    return this.batch;
  }

  /**
   * @return caching the number of rows fetched when calling next on a scanner
   */
  public int getCaching() {
    return this.caching;
  } 

  /**
   * @return TimeRange
   */
  public TimeRange getTimeRange() {
    return this.tr;
  } 
  
  /**
   * @return RowFilter
   */
  public Filter getFilter() {
    return filter;
  }

  /**
   * @return true is a filter has been specified, false if not
   */
  public boolean hasFilter() {
    return filter != null;
  }
  
  /**
   * Set whether blocks should be cached for this Scan.
   * <p>
   * This is true by default.  When true, default settings of the table and
   * family are used (this will never override caching blocks if the block
   * cache is disabled for that family or entirely).
   * 
   * @param cacheBlocks if false, default settings are overridden and blocks
   * will not be cached
   */
  public void setCacheBlocks(boolean cacheBlocks) {
    this.cacheBlocks = cacheBlocks;
  }
  
  /**
   * Get whether blocks should be cached for this Scan.
   * @return true if default caching should be used, false if blocks should not
   * be cached
   */
  public boolean getCacheBlocks() {
    return cacheBlocks;
  }
  
  /**
   * @return String
   */
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append("startRow=");
    sb.append(Bytes.toString(this.startRow));
    sb.append(", stopRow=");
    sb.append(Bytes.toString(this.stopRow));
    sb.append(", maxVersions=");
    sb.append("" + this.maxVersions);
    sb.append(", batch=");
    sb.append("" + this.batch);
    sb.append(", caching=");
    sb.append("" + this.caching);
    sb.append(", cacheBlocks=");
    sb.append("" + this.cacheBlocks);
    sb.append(", timeRange=");
    sb.append("[" + this.tr.getMin() + "," + this.tr.getMax() + ")");
    sb.append(", families=");
    if(this.familyMap.size() == 0) {
      sb.append("ALL");
      return sb.toString();
    }
    boolean moreThanOne = false;
    for(Map.Entry<byte [], NavigableSet<byte[]>> entry : this.familyMap.entrySet()) {
      if(moreThanOne) {
        sb.append("), ");
      } else {
        moreThanOne = true;
        sb.append("{");
      }
      sb.append("(family=");
      sb.append(Bytes.toString(entry.getKey()));
      sb.append(", columns=");
      if(entry.getValue() == null) {
        sb.append("ALL");
      } else {
        sb.append("{");
        boolean moreThanOneB = false;
        for(byte [] column : entry.getValue()) {
          if(moreThanOneB) {
            sb.append(", ");
          } else {
            moreThanOneB = true;
          }
          sb.append(Bytes.toString(column));
        }
        sb.append("}");
      }
    }
    sb.append("}");
    return sb.toString();
  }
  
  @SuppressWarnings("unchecked")
  private Writable createForName(String className) {
    try {
      Class<? extends Writable> clazz =
        (Class<? extends Writable>) Class.forName(className);
      return WritableFactories.newInstance(clazz, new Configuration());
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Can't find class " + className);
    }    
  }
  
  //Writable
  public void readFields(final DataInput in)
  throws IOException {
    int version = in.readByte();
    if (version > (int)SCAN_VERSION) {
      throw new IOException("version not supported");
    }
    this.startRow = Bytes.readByteArray(in);
    this.stopRow = Bytes.readByteArray(in);
    this.maxVersions = in.readInt();
    this.batch = in.readInt();
    this.caching = in.readInt();
    this.cacheBlocks = in.readBoolean();
    if(in.readBoolean()) {
      this.filter = (Filter)createForName(Bytes.toString(Bytes.readByteArray(in)));
      this.filter.readFields(in);
    }
    this.tr = new TimeRange();
    tr.readFields(in);
    int numFamilies = in.readInt();
    this.familyMap = 
      new TreeMap<byte [], NavigableSet<byte []>>(Bytes.BYTES_COMPARATOR);
    for(int i=0; i<numFamilies; i++) {
      byte [] family = Bytes.readByteArray(in);
      int numColumns = in.readInt();
      TreeSet<byte []> set = new TreeSet<byte []>(Bytes.BYTES_COMPARATOR);
      for(int j=0; j<numColumns; j++) {
        byte [] qualifier = Bytes.readByteArray(in);
        set.add(qualifier);
      }
      this.familyMap.put(family, set);
    }
  }  

  public void write(final DataOutput out)
  throws IOException {
    out.writeByte(SCAN_VERSION);
    Bytes.writeByteArray(out, this.startRow);
    Bytes.writeByteArray(out, this.stopRow);
    out.writeInt(this.maxVersions);
    out.writeInt(this.batch);
    out.writeInt(this.caching);
    out.writeBoolean(this.cacheBlocks);
    if(this.filter == null) {
      out.writeBoolean(false);
    } else {
      out.writeBoolean(true);
      Bytes.writeByteArray(out, Bytes.toBytes(filter.getClass().getName()));
      filter.write(out);
    }
    tr.write(out);
    out.writeInt(familyMap.size());
    for(Map.Entry<byte [], NavigableSet<byte []>> entry : familyMap.entrySet()) {
      Bytes.writeByteArray(out, entry.getKey());
      NavigableSet<byte []> columnSet = entry.getValue();
      if(columnSet != null){
        out.writeInt(columnSet.size());
        for(byte [] qualifier : columnSet) {
          Bytes.writeByteArray(out, qualifier);
        }
      } else {
        out.writeInt(0);
      }
    }
  }
}