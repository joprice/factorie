/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, ListBuffer, FlatHashTable}
import scala.util.{Random,Sorting}
import scala.util.Random
import scala.math
import scala.util.Sorting
import cc.factorie.la._
import cc.factorie.util.Substitutions
import java.io._

/** Related factors may be associated with a Family.  
    Those factors may share parameters or other attributes that may be stored in the Family. */
trait Family {
  type FamilyType <: Family // like a self-type
  type FactorType <: Factor
  type ValuesType <: cc.factorie.Values
  type Values = ValuesType
  type StatisticsType <: Statistics
  type NeighborType1
  @inline final def thisFamily: this.type = this
  /** The method responsible for mapping a Statistic object to a real-valued score.  
      Called by the Statistic.score method; implemented here so that it can be easily overriden in user-defined subclasses of Template. */
  //def score(s:StatisticsType): Double
  //@inline final def score(s:cc.factorie.Statistics): Double = score(s.asInstanceOf[StatisticsType])
  def defaultFactorName = this.getClass.getName
  var factorName: String = defaultFactorName
  /** Assign this Template a name which will be used later when its factors are printed. */
  def setFactorName(n:String): this.type = { factorName = n; this }
  /** Assign this Template a name which will be used later when its factors are printed. */
  def %(n:String): this.type = setFactorName(n) // because % is the comment character in shell languages such as /bin/sh and Makefiles.
  trait Factor extends cc.factorie.Factor {
    def family: FamilyType = Family.this.asInstanceOf[FamilyType];
    //def family = thisFamily
    def _1: NeighborType1
    override def values: ValuesType
    override def statistics: StatisticsType // = statistics(values)
    override def cachedStatistics: StatisticsType = thisFamily.cachedStatistics(values)
    override def factorName = family.factorName
    override def equalityPrerequisite: AnyRef = Family.this
    //@deprecated def forSettingsOf(vs:Seq[Variable])(f: =>Unit): Unit = Family.this.forSettingsOf(this.asInstanceOf[FactorType], vs)(f)
  }
  // Values
  /*trait Values extends cc.factorie.Values {
    def family: FamilyType = Family.this.asInstanceOf[FamilyType]
  }*/
  // Statistics
  trait Statistics extends cc.factorie.Statistics {
    def family: FamilyType = Family.this.asInstanceOf[FamilyType]
    //def family = thisFamily
    // TODO Make this non-lazy later, when _statisticsDomains can be initialized earlier
    // Warning: if score gets called too late, might the values of the variables have been changed to something else already?
    //lazy val score = Family.this.score(this.asInstanceOf[StatisticsType]) 
    // TODO can we find a way to get rid of this cast?  Yes, use a self-type Stat[This], but too painful
  }
  def statistics(values:ValuesType): StatisticsType
  /** May be overridden in subclasses to actually cache. */
  //def cachedStatistics(values:ValuesType, stats:(ValuesType)=>StatisticsType): StatisticsType = stats(values)
  def cachedStatistics(values:ValuesType): StatisticsType = statistics(values)
  def clearCachedStatistics: Unit = {}
  
  /** The filename into which to save this factor.  If templateName is not the default, use it, otherwise use the class name. */
  protected def filename: String = factorName
  def save(dirname:String): Unit = {}
  def load(dirname:String): Unit = {}
}



trait FamilyWithNeighborDomains extends Family {
  protected var _neighborDomains: ArrayBuffer[Domain[_]] = null
  protected def _newNeighborDomains = new ArrayBuffer[Domain[_]]
  def neighborDomains: Seq[Domain[_]] = 
    if (_neighborDomains eq null)
      throw new IllegalStateException("You must override neighborDomains if you want to access them before creating any Factor objects.")
    else
      _neighborDomains
}


/** A factor Family whose sufficient statistics are represented as a set of DiscreteVectorValues
    (which inherit from cc.factorie.la.Vector, and also have a DiscreteVectorDomain).
    @author Andrew McCallum
*/
trait VectorFamily extends Family {
  //def vectorLength: Int
  //protected var _vectorLength1 = -1
  //def vectorLength1: Int = if (_vectorLength < 0) throw new Error("Not yet set.") else _vectorLength1
  protected var _statisticsDomains: ArrayBuffer[DiscreteVectorDomain] = null
  protected def _newStatisticsDomains = new ArrayBuffer[DiscreteVectorDomain]
  def statisticsDomains: Seq[DiscreteVectorDomain] = 
    if (_statisticsDomains eq null)
      throw new IllegalStateException("You must override statisticsDomains if you want to access them before creating any Factor and Stat objects.")
    else
      _statisticsDomains
  def freezeDomains: Unit = statisticsDomains.foreach(_.freeze)
  lazy val statisticsVectorLength: Int = statisticsDomains.multiplyInts(_.dimensionSize)
  type StatisticsType <: Statistics
  trait Statistics extends super.Statistics {
    def vector: Vector
  }
  // TODO implement this!
  private def unflattenOuter(weightIndex:Int, dimensions:Int*): Array[Int] = new Array[Int](2)
}


/** A VectorTemplate that also has a vector of weights, and calculates score by a dot-product between statistics.vector and weights.
    @author Andrew McCallum */
trait DotFamily extends VectorFamily {
  //type TemplateType <: DotFamily
  type FamilyType <: DotFamily
  lazy val weights: Vector = { freezeDomains; new DenseVector(statisticsVectorLength) } // Dense by default, may be override in sub-traits
  def score(s:StatisticsType) = if (s eq null) 0.0 else weights match {
    case w:DenseVector => { w dot s.vector }
    //case w:SparseHashVector => w dot s.vector // TODO Uncomment this.  It was only commented because latest version of scalala didn't seem to have this class any more
    case w:SparseVector => w dot s.vector
  }

  override def save(dirname:String): Unit = {
    val f = new File(dirname+"/"+filename) // TODO Make this work on MSWindows also
    if (f.exists) return // Already exists, don't write it again
    for (d <- statisticsDomains) d.save(dirname)
    val writer = new PrintWriter(new FileWriter(f))
    saveToWriter(writer)
  }

  def saveToWriter(writer:PrintWriter): Unit = {
    // TODO Do we also need to save the weights.default?
    for (weight <- weights.activeElements; if (weight._2 != 0.0)) { // before June 21 2010, used too be weights.iterator -akm
      writer.print(weight._1)
      writer.print(" ")
      writer.println(weight._2)
    }
    writer.close
  }

  override def load(dirname:String): Unit = {
    //println("Loading "+this.getClass.getName+" from directory "+dirname)
    for (d <- statisticsDomains) { /* println(" Loading Domain["+d+"]"); */ d.load(dirname) }
    val f = new File(dirname+"/"+filename)
    val reader = new BufferedReader(new FileReader(f))
    loadFromReader(reader)
  }

  def loadFromReader(reader:BufferedReader): Unit = {
    // TODO Why would statisticsVectorLength be 0 or negative?
    if (statisticsVectorLength <= 0 || weights.activeElements.exists({case(i,v) => v != 0})) return // Already have non-zero weights, must already be read.
    var line = ""
    while ({ line = reader.readLine; line != null }) {
      val fields = line.split(" +")
      assert(fields.length == 2)
      val index = Integer.parseInt(fields(0))
      val value = java.lang.Double.parseDouble(fields(1))
      weights(index) = value
    }
    reader.close
  }
}


/** A DotTemplate that stores its parameters in a Scalala SparseVector instead of a DenseVector
    @author Andrew McCallum */
trait SparseWeights extends DotFamily {
  override lazy val weights: Vector = { new SparseVector(statisticsVectorLength) } // Dense by default, here overridden to be sparse
}

/** A DotTemplate that stores its parameters in a Scalala SparseHashVector instead of a DenseVector
    @author Sameer Singh */
trait SparseHashWeights extends DotFamily {
  override lazy val weights: Vector = { freezeDomains; new SparseHashVector(statisticsVectorLength) } // Dense by default, override to be sparseHashed
}

