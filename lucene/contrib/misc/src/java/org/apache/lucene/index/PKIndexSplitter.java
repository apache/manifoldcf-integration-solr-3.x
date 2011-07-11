package org.apache.lucene.index;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;

import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.TermRangeFilter;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.Version;

/**
 * Split an index based on a {@link Filter}.
 */

public class PKIndexSplitter {
  private final Filter docsInFirstIndex;
  private final Directory input;
  private final Directory dir1;
  private final Directory dir2; 
  
  /**
   * Split an index based on a {@link Filter}. All documents that match the filter
   * are sent to dir1, remaining ones to dir2.
   */
  public PKIndexSplitter(Directory input, Directory dir1, Directory dir2, Filter docsInFirstIndex) {
    this.input = input;
    this.dir1 = dir1;
    this.dir2 = dir2;
    this.docsInFirstIndex = docsInFirstIndex;
  }
  
  /**
   * Split an index based on a  given primary key term 
   * and a 'middle' term.  If the middle term is present, it's
   * sent to dir2.
   */
  public PKIndexSplitter(Directory input, Directory dir1, Directory dir2, Term midTerm) {
    this(input, dir1, dir2,
      new TermRangeFilter(midTerm.field(), null, midTerm.text(), true, false));
  }
  
  public void split() throws IOException {
    boolean success = false;
    IndexReader reader = IndexReader.open(input);
    try {
      createIndex(dir1, reader, docsInFirstIndex, false);
      createIndex(dir2, reader, docsInFirstIndex, true);
      success = true;
    } finally {
      IOUtils.closeSafely(!success, reader);
    }
  }
  
  private void createIndex(Directory target, IndexReader reader, Filter preserveFilter, boolean negateFilter) throws IOException {
    boolean success = false;
    IndexWriter w = new IndexWriter(target, new IndexWriterConfig(
        Version.LUCENE_CURRENT, null).setOpenMode(OpenMode.CREATE));
    try {
      w.addIndexes(new DocumentFilteredIndexReader(reader, preserveFilter, negateFilter));
      success = true;
    } finally {
      IOUtils.closeSafely(!success, w);
    }
  }
    
  public static class DocumentFilteredIndexReader extends FilterIndexReader {
    final FixedBitSet readerDels;
    final int numDocs;
    
    public DocumentFilteredIndexReader(IndexReader reader, Filter preserveFilter, boolean negateFilter) throws IOException {
      super(reader);
      
      final FixedBitSet bits = new FixedBitSet(in.maxDoc());
      final DocIdSet docs = preserveFilter.getDocIdSet(in);
      if (docs != null) {
        final DocIdSetIterator it = docs.iterator();
        if (it != null) {
          bits.or(it);
        }
      }
      // this is somehow inverse, if we negate the filter, we delete all documents it matches!
      if (!negateFilter) {
        bits.flip(0, in.maxDoc());
      }

      if (in.hasDeletions()) {
        for (int i = 0; i < in.maxDoc(); i++) {
          if (in.isDeleted(i)) {
            bits.set(i);
          }
        }
      }
      
      this.readerDels = bits;
      this.numDocs = in.maxDoc() - (int) bits.cardinality();
    }
    
    @Override
    public int numDocs() {
      return numDocs;
    }
    
    @Override
    public boolean hasDeletions() {
      return (in.maxDoc() != numDocs);
    }

    @Override
    public boolean isDeleted(int n) {
      return readerDels.get(n);
    }

    @Override
    public IndexReader[] getSequentialSubReaders() {
      return null;
    }

    @Override
    public TermPositions termPositions() throws IOException {
      return new FilterTermPositions(in.termPositions()) {

        @Override
        public boolean next() throws IOException {
          boolean res;
          while ((res = super.next())) {
            if (!readerDels.get(doc())) {
              break;
            }
          }
          return res;
        }        
      };
    }
  }
}
