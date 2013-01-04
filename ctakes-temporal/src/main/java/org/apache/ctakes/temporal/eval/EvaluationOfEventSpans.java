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
package org.apache.ctakes.temporal.eval;

import java.io.File;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Level;

import org.apache.ctakes.temporal.ae.EventAnnotator;
import org.apache.ctakes.temporal.ae.feature.selection.FeatureSelection;
import org.apache.ctakes.typesystem.type.textsem.EntityMention;
import org.apache.ctakes.typesystem.type.textsem.EventMention;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.TOP;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.cleartk.classifier.Instance;
import org.cleartk.classifier.feature.transform.InstanceDataWriter;
import org.cleartk.classifier.feature.transform.InstanceStream;
import org.cleartk.classifier.jar.JarClassifierBuilder;
import org.cleartk.classifier.libsvm.LIBSVMStringOutcomeDataWriter;
import org.cleartk.eval.AnnotationStatistics;
import org.uimafit.factory.AggregateBuilder;
import org.uimafit.pipeline.SimplePipeline;
import org.uimafit.util.JCasUtil;

import com.lexicalscope.jewel.cli.CliFactory;
import com.lexicalscope.jewel.cli.Option;

public class EvaluationOfEventSpans extends EvaluationOfAnnotationSpans_ImplBase {

  static interface Options extends Evaluation_ImplBase.Options {

    @Option(longName = "downratio", defaultValue = "1")
    public float getProbabilityOfKeepingANegativeExample();

    @Option(longName = "featureSelectionThreshold", defaultValue = "0")
    public float getFeatureSelectionThreshold();
    
    @Option(longName = "SMOTENeighborNumber", defaultValue = "1")
    public float getSMOTENeighborNumber();
  }

  public static void main(String[] args) throws Exception {
    Options options = CliFactory.parseArguments(Options.class, args);
    List<Integer> patientSets = options.getPatients().getList();
    List<Integer> trainItems = THYMEData.getTrainPatientSets(patientSets);
    List<Integer> devItems = THYMEData.getDevPatientSets(patientSets);
    EvaluationOfEventSpans evaluation = new EvaluationOfEventSpans(
        new File("target/eval/event-spans"),
        options.getRawTextDirectory(),
        options.getKnowtatorXMLDirectory(),
        options.getProbabilityOfKeepingANegativeExample(),
        options.getFeatureSelectionThreshold(),
        options.getSMOTENeighborNumber());
    evaluation.setLogging(Level.FINE, new File("target/eval/ctakes-event-errors.log"));
    AnnotationStatistics<String> stats = evaluation.trainAndTest(trainItems, devItems);
    System.err.println(stats);
  }

  private float probabilityOfKeepingANegativeExample;

  private float featureSelectionThreshold;
  
  private float smoteNeighborNumber;

  public EvaluationOfEventSpans(
      File baseDirectory,
      File rawTextDirectory,
      File knowtatorXMLDirectory,
      float probabilityOfKeepingANegativeExample,
      float featureSelectionThreshold, float numOfSmoteNeighbors) {
    super(baseDirectory, rawTextDirectory, knowtatorXMLDirectory, EnumSet.of(
        AnnotatorType.PART_OF_SPEECH_TAGS,
        AnnotatorType.CHUNKS,
        AnnotatorType.DEPENDENCIES,
        AnnotatorType.SEMANTIC_ROLES));
        //AnnotatorType.UMLS_NAMED_ENTITIES,
        //AnnotatorType.LEXICAL_VARIANTS,
        //AnnotatorType.DEPENDENCIES,
        //AnnotatorType.SEMANTIC_ROLES));
    this.probabilityOfKeepingANegativeExample = probabilityOfKeepingANegativeExample;
    this.featureSelectionThreshold = featureSelectionThreshold;
    this.smoteNeighborNumber = numOfSmoteNeighbors;
  }

  @Override
  protected AnalysisEngineDescription getDataWriterDescription(File directory)
      throws ResourceInitializationException {
    Class<?> dataWriterClass = this.featureSelectionThreshold > 0f
        ? InstanceDataWriter.class
        : LIBSVMStringOutcomeDataWriter.class;
    return EventAnnotator.createDataWriterDescription(
        dataWriterClass,
        directory,
        this.probabilityOfKeepingANegativeExample,
        this.featureSelectionThreshold,
        this.smoteNeighborNumber);
  }

  @Override
  protected void train(CollectionReader collectionReader, File directory) throws Exception {
    AggregateBuilder aggregateBuilder = new AggregateBuilder();
    aggregateBuilder.add(this.getPreprocessorTrainDescription());
    aggregateBuilder.add(this.getDataWriterDescription(directory));
    SimplePipeline.runPipeline(collectionReader, aggregateBuilder.createAggregate());

    if (this.featureSelectionThreshold > 0) {
      // Extracting features and writing instances
      Iterable<Instance<String>> instances = InstanceStream.loadFromDirectory(directory);
      // Collect MinMax stats for feature normalization
      FeatureSelection<String> featureSelection = EventAnnotator.createFeatureSelection(this.featureSelectionThreshold);
      featureSelection.train(instances);
      featureSelection.save(EventAnnotator.createFeatureSelectionURI(directory));
      // now write in the libsvm format
      LIBSVMStringOutcomeDataWriter dataWriter = new LIBSVMStringOutcomeDataWriter(directory);
      for (Instance<String> instance : instances) {
        dataWriter.write(featureSelection.transform(instance));
      }
      dataWriter.finish();
    }

    this.trainAndPackage(directory);
  }

  @Override
  protected void trainAndPackage(File directory) throws Exception {
    JarClassifierBuilder.trainAndPackage(directory, "-c", "10000");
  }

  @Override
  protected List<Class<? extends TOP>> getAnnotationClassesThatShouldBeGoldAtTestTime() {
    List<Class<? extends TOP>> result = super.getAnnotationClassesThatShouldBeGoldAtTestTime();
    result.add(EntityMention.class);
    return result;
  }

  @Override
  protected AnalysisEngineDescription getAnnotatorDescription(File directory)
      throws ResourceInitializationException {
    return EventAnnotator.createAnnotatorDescription(directory);
  }

  @Override
  protected Collection<? extends Annotation> getGoldAnnotations(JCas jCas) {
    return JCasUtil.select(jCas, EventMention.class);
  }

  @Override
  protected Collection<? extends Annotation> getSystemAnnotations(JCas jCas) {
    return JCasUtil.select(jCas, EventMention.class);
  }
}
