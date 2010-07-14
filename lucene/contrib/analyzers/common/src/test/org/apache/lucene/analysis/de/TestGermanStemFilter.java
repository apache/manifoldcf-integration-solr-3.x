package org.apache.lucene.analysis.de;

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

import java.io.InputStream;
import java.io.Reader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.BaseTokenStreamTestCase;
import org.apache.lucene.analysis.KeywordTokenizer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.ReusableAnalyzerBase;

import static org.apache.lucene.analysis.VocabularyAssert.*;

/**
 * Test the German stemmer. The stemming algorithm is known to work less 
 * than perfect, as it doesn't use any word lists with exceptions. We 
 * also check some of the cases where the algorithm is wrong.
 *
 */
public class TestGermanStemFilter extends BaseTokenStreamTestCase {

  public void testStemming() throws Exception {
    Analyzer analyzer = new ReusableAnalyzerBase() {
      @Override
      protected TokenStreamComponents createComponents(String fieldName,
          Reader reader) {
        Tokenizer t = new KeywordTokenizer(reader);
        return new TokenStreamComponents(t,
            new GermanStemFilter(new LowerCaseFilter(TEST_VERSION_CURRENT, t)));
      }
    };
    
    InputStream vocOut = getClass().getResourceAsStream("data.txt");
    assertVocabulary(analyzer, vocOut);
    vocOut.close();
  }
}
