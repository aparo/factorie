package cc.factorie

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, ListBuffer, FlatHashTable}
import scala.reflect.Manifest
import scala.util.Random
import scala.Math
import scala.util.Sorting
import scalala.tensor.Vector
import scalala.tensor.dense.DenseVector
import scalala.tensor.sparse.{SparseVector, SparseBinaryVector, SingletonBinaryVector}
import cc.factorie.util.{Log, ConsoleLogging, LinkedHashSet}
import cc.factorie.util.Implicits._

/**Abstract superclass of all variables.  Don't need to know its value type to use it. 
   The trait is abstract not because you should not instantiate this trait directly, only subclasses */ 
abstract trait Variable {
  /** The type of this variable, especially used by this Variable's Domain. */
	type VariableType <: Variable
	/** The type of this.domain and Domain[MyVariable]*/
	type DomainType <: Domain[VariableType]
  /** When a Domain is constructed for this class, it will be the superclass of this inner class. */
  class DomainClass extends Domain[VariableType]
  /** When DomainInSubclasses appears as an inner class of a Variable class, 
      it simply ensures that the library will never create a Domain for this class, only its subclasses.
      If library users create their own new Variable classes, which will be subclassed, and wants each
      subclass to have its own Domain, then those new Variable classes must declare an inner class of this type. */
  class DomainInSubclasses
  final def domain = Domain.get[VariableType](this.getClass)
	private def shortClassName = {
	  var fields = this.getClass.getName.split('$')
	  if (fields.length == 1)
	  	fields = this.getClass.getName.split('.')
	  if (fields.last == "class")
	  	fields(fields.length - 2)
	  else if ("1234567890".contains(fields.last))
     fields(fields.length-2)
    else
      fields.last
	}
	def printName = shortClassName
	override def toString = printName + "(_)"
	def factors(model:Model): Iterable[Factor] = model.factors(this)
}

/** Used as a marker for Variables whose value does not change once created.  
    Be  careful to only use this in class definitions that cannot become mutable in subclasses. */
@deprecated
abstract trait ConstantValue requires Variable

/** For variables whose value has a type, indicated in type ValueType */
abstract trait TypedVariable {
  this : Variable =>
	type ValueType
}



// TODO remove this now that we have Proposer
/**A variable that can provide an Iterator over all of its values. */
@deprecated//("Use IterableSettings instead")
abstract trait IterableValues[T] {
	// TODO Inherit from TypedVariable instead?
			this: Variable =>
	/**Values this variable could take on. */
	def iterableValues: Iterable[T];
	/**Possible alternative values, that is, values other than its current value. */
	def iterableOtherValues: Iterable[T]
}

/** A variable that can iterate over its possible configurations */
trait IterableSettings{
	this: Variable =>
  /** Return an iterator over some/all configurations of this variable, each time returning simply the same Variable object. */
  // TODO this "settings" method should take a DiffList argument so it can see what else has changed, to avoid loops?
  // No, we need something even more powerful, because we'd like to enable nested for-comprehensions of changes to multiple variables.
  // Think about this some more!!!!
  def settings: Iterator[{def set(d:DiffList):Unit}]
}
// TODO Remove this?
trait Setting {
  def set(d:DiffList) : Unit
}

/** A variable for which the true, correct value is known.  Often used for target variables inferred at training time. */
trait TrueSetting {
  this: Variable =>
  def setToTruth(implicit d:DiffList): Unit
  def valueIsTruth: Boolean
}


// TODO could this be put in TypedVariable?  But then we'd have to figure out how to compare VectorVariable
trait PrimitiveComparison[T] {
  this :Variable =>
  def value : T
  def ===(other: PrimitiveComparison[T]) = value == other.value
  def !==(other: PrimitiveComparison[T]) = value != other.value

}

abstract class PrimitiveObservation[T](theValue:T) extends Variable with TypedVariable with PrimitiveComparison[T] {
	type VariableType <: PrimitiveObservation[T]
  type ValueType = T
  class DomainInSubclasses
  val value: T = theValue
  override def toString = printName + "(" + value.toString + ")"
}

/**A variable with a single mutable (unindexed) value which is of Scala type T. */
// TODO A candidate for Scala 2.8 @specialized
abstract class PrimitiveVariable[T] extends Variable with TypedVariable with PrimitiveComparison[T] {
  def this(initval:T) = { this(); set(initval)(null) } // initialize like this because subclasses may do coordination in overridden set()()
	type VariableType <: PrimitiveVariable[T]
  type ValueType = T
  class DomainInSubclasses
  protected var _value: T = _
  def value = _value
  def set(newValue: T)(implicit d: DiffList): Unit =
    if (newValue != _value) {
      if (d != null) d += new PrimitiveDiff(_value, newValue)
      _value = newValue
    }
  def :=(newValue:T) = set(newValue)(null)
  override def toString = printName + "(" + value.toString + ")"
	case class PrimitiveDiff(oldValue: T, newValue: T) extends Diff {
  	//        Console.println ("new PrimitiveDiff old="+oldValue+" new="+newValue)
  	def variable: PrimitiveVariable[T] = PrimitiveVariable.this
  	def redo = _value = newValue
  	def undo = _value = oldValue
  }
}

/** For variables that have a true value described by a Scala type T. */
trait PrimitiveTrueValue[T] extends TrueSetting {
	this: PrimitiveVariable[T] =>
  var trueValue: T = _
  def isUnlabeled = trueValue == _
  def setToTruth(implicit d:DiffList): Unit = set(trueValue)
  def valueIsTruth: Boolean = trueValue == value
}


/** For Variables that hold their list of Factors */
trait FactorList {
	this : Variable =>
  private var factorList: List[Factor] = Nil
  def addFactor(f: Factor) = factorList = f :: factorList
  def clearFactors = factorList = Nil
  def factors: Iterable[Factor] = factorList
}


// The two traits below may enable efficiencies for sampling, scoring and learning
// But they are currently unused.
// TODO: consider removing them

/** A marker for Variables that declare themselves not to automatically change other Variables' values when they are changed */
trait NoVariableCoordination 

/** A marker for Variables that declare themselves not to only to rely on their own Factors when they are changed,
    even if changing this variable involves changes to other variables as well. 
    Furthermore the section of Factors must not change depending on the variable's values. */
trait NoFactorCoordination 


