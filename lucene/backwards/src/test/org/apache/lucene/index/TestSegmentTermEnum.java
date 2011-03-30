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

import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;


public class TestSegmentTermEnum extends LuceneTestCase {
  
  Directory dir;
  
  @Override
  public void setUp() throws Exception {
    super.setUp();
    dir = newDirectory();
  }
  
  @Override
  public void tearDown() throws Exception {
    dir.close();
    super.tearDown();
  }

  public void testTermEnum() throws IOException {
    IndexWriter writer = null;

    writer  = new IndexWriter(dir, newIndexWriterConfig(TEST_VERSION_CURRENT, new WhitespaceAnalyzer(TEST_VERSION_CURRENT)));

    // ADD 100 documents with term : aaa
    // add 100 documents with terms: aaa bbb
    // Therefore, term 'aaa' has document frequency of 200 and term 'bbb' 100
    for (int i = 0; i < 100; i++) {
      addDoc(writer, "aaa");
      addDoc(writer, "aaa bbb");
    }

    writer.close();

    // verify document frequency of terms in an unoptimized index
    verifyDocFreq();

    // merge segments by optimizing the index
    writer = new IndexWriter(dir, newIndexWriterConfig(TEST_VERSION_CURRENT, new WhitespaceAnalyzer(TEST_VERSION_CURRENT)).setOpenMode(OpenMode.APPEND));
    writer.optimize();
    writer.close();

    // verify document frequency of terms in an optimized index
    verifyDocFreq();
  }

  public void testPrevTermAtEnd() throws IOException
  {
    IndexWriter writer  = new IndexWriter(dir, newIndexWriterConfig(TEST_VERSION_CURRENT, new WhitespaceAnalyzer(TEST_VERSION_CURRENT)));
    addDoc(writer, "aaa bbb");
    writer.close();
    SegmentReader reader = SegmentReader.getOnlySegmentReader(dir);
    SegmentTermEnum termEnum = (SegmentTermEnum) reader.terms();
    assertTrue(termEnum.next());
    assertEquals("aaa", termEnum.term().text());
    assertTrue(termEnum.next());
    assertEquals("aaa", termEnum.prev().text());
    assertEquals("bbb", termEnum.term().text());
    assertFalse(termEnum.next());
    assertEquals("bbb", termEnum.prev().text());
    reader.close();
  }

  private void verifyDocFreq()
      throws IOException
  {
      IndexReader reader = IndexReader.open(dir, true);
      TermEnum termEnum = null;

    // create enumeration of all terms
    termEnum = reader.terms();
    // go to the first term (aaa)
    termEnum.next();
    // assert that term is 'aaa'
    assertEquals("aaa", termEnum.term().text());
    assertEquals(200, termEnum.docFreq());
    // go to the second term (bbb)
    termEnum.next();
    // assert that term is 'bbb'
    assertEquals("bbb", termEnum.term().text());
    assertEquals(100, termEnum.docFreq());

    termEnum.close();


    // create enumeration of terms after term 'aaa', including 'aaa'
    termEnum = reader.terms(new Term("content", "aaa"));
    // assert that term is 'aaa'
    assertEquals("aaa", termEnum.term().text());
    assertEquals(200, termEnum.docFreq());
    // go to term 'bbb'
    termEnum.next();
    // assert that term is 'bbb'
    assertEquals("bbb", termEnum.term().text());
    assertEquals(100, termEnum.docFreq());
    termEnum.close();
    reader.close();
  }

  private void addDoc(IndexWriter writer, String value) throws IOException
  {
    Document doc = new Document();
    doc.add(newField("content", value, Field.Store.NO, Field.Index.ANALYZED));
    writer.addDocument(doc);
  }
}
