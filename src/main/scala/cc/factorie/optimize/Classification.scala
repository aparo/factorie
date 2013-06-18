package cc.factorie.optimize

import cc.factorie._
import cc.factorie.la.{DenseTensor2, Tensor1, DenseTensor1}

/**
 * User: apassos
 * Date: 6/12/13
 * Time: 3:54 PM
 */


trait BaseClassification[Pred] {
  def score: Pred
  def proportions: Proportions
}

trait Classifier[Pred, Features] {
  def score(features: Features): Pred
  def classification(features: Features): BaseClassification[Pred]
}

class BinaryClassification(val score: Double) extends BaseClassification[Double] {
  lazy val proportions = {
    val t = new DenseTensor1(2)
    t(1) = LinearObjectives.logisticLinkFunction(score)
    t(0) = 1.0-t(1)
    new DenseTensorProportions1(t)
  }
  lazy val bestValue = score > 0
}

class MultiClassClassification(val score: Tensor1) extends BaseClassification[Tensor1] {
  lazy val proportions = new DenseTensorProportions1(score.expNormalized.asInstanceOf[Tensor1])
  lazy val bestLabelIndex = proportions.maxIndex
}

trait BaseBinaryClassifier[Features] extends Classifier[Double, Features] {
  def classification(features: Features) = new BinaryClassification(score(features))
}

trait MultiClassClassifier[Features] extends Classifier[Tensor1, Features] {
  def classification(features: Features) = new MultiClassClassification(score(features))
}

trait MultiClassTrainerBase[+C <: MultiClassClassifier[Tensor1]] {
  def simpleTrain(labelSize: Int, featureSize: Int, labels: Seq[Int], features: Seq[Tensor1], weights: Seq[Double], evaluate: C => Unit): C

  def train(labels: Seq[LabeledDiscreteVar], features: Seq[DiscreteTensorVar], weights: Seq[Double], testLabels: Seq[LabeledDiscreteVar], testFeatures: Seq[TensorVar]): C= {
    val evaluate = (c: C) => println(f"Test accuracy: ${testFeatures.map(i => c.classification(i.value.asInstanceOf[Tensor1]).bestLabelIndex)
                                                                                         .zip(testLabels).count(i => i._1 == i._2.targetIntValue).toDouble/testLabels.length}%1.4f")
    simpleTrain(labels.head.domain.size, features.head.domain.dimensionSize, labels.map(_.targetIntValue), features.map(_.value), weights, evaluate)
  }
  def train(labels: Seq[LabeledDiscreteVar], features: Seq[DiscreteTensorVar], testLabels: Seq[LabeledDiscreteVar], testFeatures: Seq[TensorVar]): C =
    train(labels, features, labels.map(i => 1.0), testLabels, testFeatures)
  def train(labels: Seq[LabeledDiscreteVar], features: Seq[DiscreteTensorVar], weights: Seq[Double]): C =
    simpleTrain(labels.head.domain.size, features.head.domain.dimensionSize, labels.map(_.targetIntValue), features.map(_.value), weights, c => ())
  def train(labels: Seq[LabeledDiscreteVar], features: Seq[DiscreteTensorVar]): C =
    simpleTrain(labels.head.domain.size, features.head.domain.dimensionSize, labels.map(_.targetIntValue), features.map(_.value), labels.map(i => 1.0), c => ())
  def train(labels: Seq[LabeledDiscreteVar], features: Seq[DiscreteTensorVar], weights: Seq[Double], evaluate: C => Unit): C =
    simpleTrain(labels.head.domain.size, features.head.domain.dimensionSize, labels.map(_.targetIntValue), features.map(_.value), weights, evaluate)
  def train(labels: Seq[LabeledDiscreteVar], features: Seq[DiscreteTensorVar], evaluate: C => Unit): C =
    simpleTrain(labels.head.domain.size, features.head.domain.dimensionSize, labels.map(_.targetIntValue), features.map(_.value), labels.map(i => 1.0), evaluate)
  def train[Label<:LabeledDiscreteVar](labels: Seq[Label], l2f: Label => DiscreteTensorVar, testLabels: Seq[Label], l2w: Label => Double = (l: Label) => 1.0): C =
    train(labels, labels.map(l2f), labels.map(l2w), testLabels, testLabels.map(l2f))
  def train[Label<:LabeledDiscreteVar](labels: Seq[Label], l2f: Label => DiscreteTensorVar, l2w: Label => Double = (l: Label) => 1.0): C =
    train(labels, labels.map(l2f), labels.map(l2w))
}

class ClassifierTemplate2[T <: DiscreteVar](l2f: T => TensorVar, classifier: MultiClassClassifier[Tensor1])(implicit ml: Manifest[T], mf: Manifest[TensorVar]) extends Template2[T, TensorVar] {
  def unroll1(v: T) = Factor(v, l2f(v))
  def unroll2(v: TensorVar) = Nil
  def score(v1: T#Value, v2: TensorVar#Value): Double = classifier.score(v2.asInstanceOf[Tensor1])(v1.asInstanceOf[DiscreteValue].intValue)
}

class LinearBinaryClassifier(val featureSize: Int) extends BaseBinaryClassifier[Tensor1] with Parameters {
  val weights = Weights(new DenseTensor1(featureSize))
  def score(features: Tensor1) = weights.value.dot(features)
}

class LinearMultiClassClassifier(val labelSize: Int, val featureSize: Int) extends MultiClassClassifier[Tensor1] with Parameters {
  self =>
  val weights = Weights(new DenseTensor2(labelSize, featureSize))
  def score(features: Tensor1) = weights.value * features
  def asTemplate[T <: LabeledMutableDiscreteVar[_]](l2f: T => TensorVar)(implicit ml: Manifest[T]) = new DotTemplateWithStatistics2[T,TensorVar] {
    def unroll1(v: T) = Factor(v, l2f(v))
    def unroll2(v: TensorVar) = Nil
    val weights = self.weights
  }
}

class LinearMultiClassTrainer(val optimizer: GradientOptimizer,
                        val useParallelTrainer: Boolean,
                        val useOnlineTrainer: Boolean,
                        val shouldAverage: Boolean,
                        val objective: LinearObjectives.MultiClass,
                        val maxIterations: Int) extends MultiClassTrainerBase[LinearMultiClassClassifier] {
  def simpleTrain(labelSize: Int, featureSize: Int, labels: Seq[Int], features: Seq[Tensor1], weights: Seq[Double], evaluate: LinearMultiClassClassifier => Unit) = {
    val classifier = new LinearMultiClassClassifier(labelSize, featureSize)
    val examples = (0 until labels.length).map(i => new LinearMultiClassExample(classifier.weights, features(i), labels(i), objective, weight=weights(i)))
    Trainer.train(parameters=classifier.parameters, examples=examples, maxIterations=maxIterations, evaluate = () => evaluate(classifier), optimizer=optimizer, useParallelTrainer=useParallelTrainer, useOnlineTrainer=useOnlineTrainer)
    classifier
  }
}

class OnlineLinearMultiClassTrainer(useParallel:Boolean = false,
                              shouldAverage:Boolean = true,
                              optimizer: GradientOptimizer = new AdaGrad with ParameterAveraging,
                              objective: LinearObjectives.MultiClass = LinearObjectives.sparseLogMultiClass,
                              maxIterations: Int = 3)
  extends LinearMultiClassTrainer(optimizer, useParallel, useOnlineTrainer = true, shouldAverage, objective, maxIterations) {}

class BatchLinearMultiClassTrainer(useParallel:Boolean = true,
                             optimizer: GradientOptimizer = new LBFGS with L2Regularization,
                             objective: LinearObjectives.MultiClass = LinearObjectives.sparseLogMultiClass,
                             maxIterations: Int = 200)
  extends LinearMultiClassTrainer(optimizer, useParallel, useOnlineTrainer = false, shouldAverage=false, objective, maxIterations) {}

