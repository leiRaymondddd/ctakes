/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ctakes.temporal.nn.data;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.ctakes.temporal.duration.Utils;
import org.apache.ctakes.temporal.eval.CommandLine;
import org.apache.ctakes.temporal.eval.THYMEData;
import org.apache.ctakes.typesystem.type.relation.BinaryTextRelation;
import org.apache.ctakes.typesystem.type.syntax.BaseToken;
import org.apache.ctakes.typesystem.type.textsem.EventMention;
import org.apache.ctakes.typesystem.type.textspan.Sentence;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CASException;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.fit.component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.pipeline.SimplePipeline;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;

import com.lexicalscope.jewel.cli.CliFactory;
import com.lexicalscope.jewel.cli.Option;

/**
 * Print gold standard relations and their context.
 * 
 * @author dmitriy dligach
 */
public class EventEventRelPrinter {

  static interface Options {

    @Option(longName = "xmi-dir")
    public File getInputDirectory();

    @Option(longName = "patients")
    public CommandLine.IntegerRanges getPatients();

    @Option(longName = "output-train")
    public File getTrainOutputDirectory();

    @Option(longName = "output-test")
    public File getTestOutputDirectory();
  }

  public static void main(String[] args) throws Exception {

    Options options = CliFactory.parseArguments(Options.class, args);

    File trainFile = options.getTrainOutputDirectory();
    if(trainFile.exists()) {
      trainFile.delete();
    }
    trainFile.createNewFile();
    File devFile = options.getTestOutputDirectory();
    if(devFile.exists()) {
      devFile.delete();
    }
    devFile.createNewFile();

    List<Integer> patientSets = options.getPatients().getList();
    List<Integer> trainItems = THYMEData.getPatientSets(patientSets, THYMEData.TRAIN_REMAINDERS);
    List<Integer> devItems = THYMEData.getPatientSets(patientSets, THYMEData.DEV_REMAINDERS);

    List<File> trainFiles = Utils.getFilesFor(trainItems, options.getInputDirectory());
    List<File> devFiles = Utils.getFilesFor(devItems, options.getInputDirectory());

    // write training data to file
    CollectionReader trainCollectionReader = Utils.getCollectionReader(trainFiles);
    AnalysisEngine trainDataWriter = AnalysisEngineFactory.createEngine(
        RelationSnippetPrinter.class,
        "IsTraining",
        true,
        "OutputFile",
        trainFile.getAbsoluteFile());
    SimplePipeline.runPipeline(trainCollectionReader, trainDataWriter);

    // write dev data to file
    CollectionReader devCollectionReader = Utils.getCollectionReader(devFiles);
    AnalysisEngine devDataWriter = AnalysisEngineFactory.createEngine(
        RelationSnippetPrinter.class,
        "IsTraining",
        false,
        "OutputFile",
        devFile.getAbsolutePath());
    SimplePipeline.runPipeline(devCollectionReader, devDataWriter);
  }

  /**
   * Print gold standard relations and their context.
   * 
   * @author dmitriy dligach
   */
  public static class RelationSnippetPrinter extends JCasAnnotator_ImplBase {

    @ConfigurationParameter(
        name = "IsTraining",
        mandatory = true,
        description = "are we training?")
    private boolean isTraining;
    
    @ConfigurationParameter(
        name = "OutputFile",
        mandatory = true,
        description = "path to the output file")
    private String outputFile;

    private Random coin = new Random(0);
    
    @Override
    public void process(JCas jCas) throws AnalysisEngineProcessException {
      
      JCas goldView;
      try {
        goldView = jCas.getView("GoldView");
      } catch (CASException e) {
        throw new AnalysisEngineProcessException(e);
      }

      JCas systemView;
      try {
        systemView = jCas.getView("_InitialView");
      } catch (CASException e) {
        throw new AnalysisEngineProcessException(e);
      }

      // can't iterate over binary text relations in a sentence, so need
      // a lookup from pair of annotations to binary text relation
      Map<List<Annotation>, BinaryTextRelation> relationLookup = new HashMap<>();
      for(BinaryTextRelation relation : JCasUtil.select(goldView, BinaryTextRelation.class)) {
        Annotation arg1 = relation.getArg1().getArgument();
        Annotation arg2 = relation.getArg2().getArgument();
        relationLookup.put(Arrays.asList(arg1, arg2), relation);
      }

      // go over sentences, extracting event-event relation instances
      for(Sentence sentence : JCasUtil.select(systemView, Sentence.class)) {
        List<String> eventEventRelationsInSentence = new ArrayList<>();
        ArrayList<EventMention> eventMentionsInSentence = new ArrayList<>(JCasUtil.selectCovered(goldView, EventMention.class, sentence));

        // retrieve event-event relations in this sentence
        for(int i = 0; i < eventMentionsInSentence.size(); i++) {
          for(int j = i + 1; j < eventMentionsInSentence.size(); j++) {
            EventMention mention1 = eventMentionsInSentence.get(i);
            EventMention mention2 = eventMentionsInSentence.get(j);
            BinaryTextRelation forwardRelation = relationLookup.get(Arrays.asList(mention1, mention2));
            BinaryTextRelation reverseRelation = relationLookup.get(Arrays.asList(mention2, mention1));

            String label = "none";            
            if(forwardRelation != null) {
              if(forwardRelation.getCategory().equals("CONTAINS")) {
                label = "contains";   // mention1 contains mention2
              }
            } else if(reverseRelation != null) {
              if(reverseRelation.getCategory().equals("CONTAINS")) {
                label = "contains-1"; // mention2 contains mention1
              }
            } 

            // sanity check
            if(mention1.getBegin() > mention2.getBegin())  {
              System.out.println("We assumed mention1 is always before mention2");
              System.out.println(sentence.getCoveredText());
              System.out.println(mention1.getCoveredText());
              System.out.println(mention2.getCoveredText());
              System.out.println();
            }

            // drop some portion of negative examples during training
            if(isTraining && label.equals("none") && coin.nextDouble() <= 0.5) {
              continue; // skip this negative example
            }
            
            String context = getTokensBetween(systemView, sentence, mention1, "e1", mention2, "e2", 2);
            // String context = getRegions(systemView, sentence, mention1, mention2, 2);
            String text = String.format("%s|%s", label, context);
            eventEventRelationsInSentence.add(text.toLowerCase());
          }
        }

        try {
          Files.write(Paths.get(outputFile), eventEventRelationsInSentence, StandardOpenOption.APPEND);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  /**
   * Return tokens between arg1 and arg2 as string 
   * @param contextSize number of tokens to include on the left of arg1 and on the right of arg2
   */
  public static String getRegions(JCas jCas, Sentence sent, Annotation left, Annotation right, int contextSize) {

    
    // tokens to the left from the left argument and the argument itself
    List<String> leftTokens = new ArrayList<>();
    for(BaseToken baseToken :  JCasUtil.selectPreceding(jCas, BaseToken.class, left, contextSize)) {
      if(sent.getBegin() <= baseToken.getBegin()) {
        leftTokens.add(baseToken.getCoveredText()); 
      }
    }
    for(BaseToken baseToken : JCasUtil.selectCovered(jCas, BaseToken.class, left)) {
      leftTokens.add(baseToken.getCoveredText());
    }
    String leftAsString = String.join(" ", leftTokens).replaceAll("[\r\n]", " ");
    
    // tokens between the arguments
    List<String> betweenTokens = new ArrayList<>();
    for(BaseToken baseToken : JCasUtil.selectBetween(jCas, BaseToken.class, left, right)) {
      betweenTokens.add(baseToken.getCoveredText());
    }
    String betweenAsString = String.join(" ", betweenTokens).replaceAll("[\r\n]", " ");
    
    // tokens to the right from the right argument and the argument itself
    List<String> rightTokens = new ArrayList<>();
    for(BaseToken baseToken : JCasUtil.selectCovered(jCas, BaseToken.class, right)) {
      rightTokens.add(baseToken.getCoveredText());
    }
    for(BaseToken baseToken : JCasUtil.selectFollowing(jCas, BaseToken.class, right, contextSize)) {
      if(baseToken.getEnd() <= sent.getEnd()) {
        rightTokens.add(baseToken.getCoveredText());
      }
    }
    String rightAsString = String.join(" ", rightTokens).replaceAll("[\r\n]", " ");
    
    return leftAsString + "|" + betweenAsString + "|" + rightAsString;
  }
  
  /**
   * Return tokens between arg1 and arg2 as string 
   * @param contextSize number of tokens to include on the left of arg1 and on the right of arg2
   */
  public static String getTokensBetween(
      JCas jCas, 
      Sentence sent, 
      Annotation left,
      String leftType,
      Annotation right,
      String rightType,
      int contextSize) {

    List<String> tokens = new ArrayList<>();
    for(BaseToken baseToken :  JCasUtil.selectPreceding(jCas, BaseToken.class, left, contextSize)) {
      if(sent.getBegin() <= baseToken.getBegin()) {
        tokens.add(baseToken.getCoveredText()); 
      }
    }
    tokens.add("<" + leftType + ">");
    tokens.add(left.getCoveredText());
    tokens.add("</" + leftType + ">");
    for(BaseToken baseToken : JCasUtil.selectBetween(jCas, BaseToken.class, left, right)) {
      tokens.add(baseToken.getCoveredText());
    }
    tokens.add("<" + rightType + ">");
    tokens.add(right.getCoveredText());
    tokens.add("</" + rightType + ">");
    for(BaseToken baseToken : JCasUtil.selectFollowing(jCas, BaseToken.class, right, contextSize)) {
      if(baseToken.getEnd() <= sent.getEnd()) {
        tokens.add(baseToken.getCoveredText());
      }
    }

    return String.join(" ", tokens).replaceAll("[\r\n]", " ");
  }
}