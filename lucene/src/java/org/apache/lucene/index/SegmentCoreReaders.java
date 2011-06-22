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
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.store.CompoundFileDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IndexInput;

/** Holds core readers that are shared (unchanged) when
 * SegmentReader is cloned or reopened */
final class SegmentCoreReaders {

  // Counts how many other reader share the core objects
  // (freqStream, proxStream, tis, etc.) of this reader;
  // when coreRef drops to 0, these core objects may be
  // closed.  A given instance of SegmentReader may be
  // closed, even those it shares core objects with other
  // SegmentReaders:
  private final AtomicInteger ref = new AtomicInteger(1);

  final String segment;
  final FieldInfos fieldInfos;
  final IndexInput freqStream;
  final IndexInput proxStream;
  final TermInfosReader tisNoIndex;

  final Directory dir;
  final Directory cfsDir;
  final int readBufferSize;
  final int termsIndexDivisor;

  private final SegmentReader owner;

  TermInfosReader tis;
  FieldsReader fieldsReaderOrig;
  TermVectorsReader termVectorsReaderOrig;
  CompoundFileDirectory cfsReader;
  CompoundFileDirectory storeCFSReader;

  SegmentCoreReaders(SegmentReader owner, Directory dir, SegmentInfo si, int readBufferSize, int termsIndexDivisor) throws IOException {
    segment = si.name;
    this.readBufferSize = readBufferSize;
    this.dir = dir;

    boolean success = false;

    try {
      Directory dir0 = dir;
      if (si.getUseCompoundFile()) {
        cfsReader = dir.openCompoundInput(IndexFileNames.segmentFileName(segment, IndexFileNames.COMPOUND_FILE_EXTENSION), readBufferSize);
        dir0 = cfsReader;
      }
      cfsDir = dir0;

      fieldInfos = new FieldInfos(cfsDir, IndexFileNames.segmentFileName(segment, IndexFileNames.FIELD_INFOS_EXTENSION));

      this.termsIndexDivisor = termsIndexDivisor;
      TermInfosReader reader = new TermInfosReader(cfsDir, segment, fieldInfos, readBufferSize, termsIndexDivisor);
      if (termsIndexDivisor == -1) {
        tisNoIndex = reader;
      } else {
        tis = reader;
        tisNoIndex = null;
      }

      // make sure that all index files have been read or are kept open
      // so that if an index update removes them we'll still have them
      freqStream = cfsDir.openInput(IndexFileNames.segmentFileName(segment, IndexFileNames.FREQ_EXTENSION), readBufferSize);

      if (fieldInfos.hasProx()) {
        proxStream = cfsDir.openInput(IndexFileNames.segmentFileName(segment, IndexFileNames.PROX_EXTENSION), readBufferSize);
      } else {
        proxStream = null;
      }
      success = true;
    } finally {
      if (!success) {
        decRef();
      }
    }

    // Must assign this at the end -- if we hit an
    // exception above core, we don't want to attempt to
    // purge the FieldCache (will hit NPE because core is
    // not assigned yet).
    this.owner = owner;
  }

  synchronized TermVectorsReader getTermVectorsReaderOrig() {
    return termVectorsReaderOrig;
  }

  synchronized FieldsReader getFieldsReaderOrig() {
    return fieldsReaderOrig;
  }

  synchronized void incRef() {
    ref.incrementAndGet();
  }

  synchronized Directory getCFSReader() {
    return cfsReader;
  }

  synchronized TermInfosReader getTermsReader() {
    if (tis != null) {
      return tis;
    } else {
      return tisNoIndex;
    }
  }      

  synchronized boolean termsIndexIsLoaded() {
    return tis != null;
  }      

  // NOTE: only called from IndexWriter when a near
  // real-time reader is opened, or applyDeletes is run,
  // sharing a segment that's still being merged.  This
  // method is not fully thread safe, and relies on the
  // synchronization in IndexWriter
  synchronized void loadTermsIndex(SegmentInfo si, int termsIndexDivisor) throws IOException {
    if (tis == null) {
      Directory dir0;
      if (si.getUseCompoundFile()) {
        // In some cases, we were originally opened when CFS
        // was not used, but then we are asked to open the
        // terms reader with index, the segment has switched
        // to CFS
        if (cfsReader == null) {
          cfsReader = dir.openCompoundInput(IndexFileNames.segmentFileName(segment, IndexFileNames.COMPOUND_FILE_EXTENSION), readBufferSize);
        }
        dir0 = cfsReader;
      } else {
        dir0 = dir;
      }

      tis = new TermInfosReader(dir0, segment, fieldInfos, readBufferSize, termsIndexDivisor);
    }
  }

  synchronized void decRef() throws IOException {

    if (ref.decrementAndGet() == 0) {

      // close everything, nothing is shared anymore with other readers
      if (tis != null) {
        tis.close();
        // null so if an app hangs on to us we still free most ram
        tis = null;
      }
      
      if (tisNoIndex != null) {
        tisNoIndex.close();
      }
      
      if (freqStream != null) {
        freqStream.close();
      }

      if (proxStream != null) {
        proxStream.close();
      }

      if (termVectorsReaderOrig != null) {
        termVectorsReaderOrig.close();
      }

      if (fieldsReaderOrig != null) {
        fieldsReaderOrig.close();
      }

      if (cfsReader != null) {
        cfsReader.close();
      }

      if (storeCFSReader != null) {
        storeCFSReader.close();
      }

      // Now, notify any ReaderFinished listeners:
      if (owner != null) {
        owner.notifyReaderFinishedListeners();
      }
    }
  }

  synchronized void openDocStores(SegmentInfo si) throws IOException {

    assert si.name.equals(segment);

    if (fieldsReaderOrig == null) {
      final Directory storeDir;
      if (si.getDocStoreOffset() != -1) {
        if (si.getDocStoreIsCompoundFile()) {
          assert storeCFSReader == null;
          storeCFSReader = dir.openCompoundInput(
              IndexFileNames.segmentFileName(si.getDocStoreSegment(), IndexFileNames.COMPOUND_FILE_STORE_EXTENSION),
                                                  readBufferSize);
          storeDir = storeCFSReader;
          assert storeDir != null;
        } else {
          storeDir = dir;
          assert storeDir != null;
        }
      } else if (si.getUseCompoundFile()) {
        // In some cases, we were originally opened when CFS
        // was not used, but then we are asked to open doc
        // stores after the segment has switched to CFS
        if (cfsReader == null) {
          cfsReader = dir.openCompoundInput(IndexFileNames.segmentFileName(segment, IndexFileNames.COMPOUND_FILE_EXTENSION), readBufferSize);
        }
        storeDir = cfsReader;
        assert storeDir != null;
      } else {
        storeDir = dir;
        assert storeDir != null;
      }

      final String storesSegment;
      if (si.getDocStoreOffset() != -1) {
        storesSegment = si.getDocStoreSegment();
      } else {
        storesSegment = segment;
      }

      fieldsReaderOrig = new FieldsReader(storeDir, storesSegment, fieldInfos, readBufferSize,
                                          si.getDocStoreOffset(), si.docCount);

      // Verify two sources of "maxDoc" agree:
      if (si.getDocStoreOffset() == -1 && fieldsReaderOrig.size() != si.docCount) {
        throw new CorruptIndexException("doc counts differ for segment " + segment + ": fieldsReader shows " + fieldsReaderOrig.size() + " but segmentInfo shows " + si.docCount);
      }

      if (si.getHasVectors()) { // open term vector files only as needed
        termVectorsReaderOrig = new TermVectorsReader(storeDir, storesSegment, fieldInfos, readBufferSize, si.getDocStoreOffset(), si.docCount);
      }
    }
  }
}
