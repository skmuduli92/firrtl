
package tutorial
package lesson3

// Compiler Infrastructure
import firrtl.ir.{DefRegister, Literal}
import firrtl.{CircuitState, LowForm, Transform, Utils}
// Firrtl IR classes
import firrtl.ir._
// Map functions
import firrtl.Mappers._
// Scala's mutable collections
import scala.collection.mutable


/*
 This program is to only get the name of the modules in the FIRRTL file
 */

class Ledger {

  private var moduleName: Option[String] = None
  private val modules = mutable.Set[String]()
  private var muxCount = 0

  def setModuleName(modName: String) : Unit = {
    modules += modName
    moduleName = Some(modName)
  }

  def serialize: String = {
    modules map {modName => s"$modName"} mkString "\n"
  }

  def incrCount() = {
    muxCount += 1
  }

  def getMuxCount : Int = {
    muxCount
  }
}

class AnalyzeCircuit extends Transform {
  /** Requires the [[firrtl.ir.Circuit Circuit]] form to be "low" */
  def inputForm = LowForm
  /** Indicates the output [[firrtl.ir.Circuit Circuit]] form to be "low" */
  def outputForm = LowForm

  def execute(state: CircuitState): CircuitState = {
    val ledger = new Ledger()
    val circuit = state.circuit

    circuit.map(walkModule(ledger))

    println(ledger.serialize)

    println("mux count : " + ledger.getMuxCount)

    state
  }

  def walkModule(ledger: Ledger)(m: DefModule) : DefModule = {
    ledger.setModuleName(m.name)

    // traverse statement node
    m.map(walkStatement(ledger))

  }

  def walkStatement(ledger: Ledger) (s: Statement): Statement = {

    s match {
      case r: DefRegister =>
        println("Register found: " + r.name)
      case w: DefWire =>
        println("Defwire : " + w.name)
      case _ =>
    }

    s map walkExpression(ledger)

    s map walkStatement(ledger)
  }

  def walkExpression(ledger: Ledger) (e: Expression): Expression = {


    e
  }

}
