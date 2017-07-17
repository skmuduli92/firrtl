// See LICENSE for license details.

package firrtl
package ir

import Utils.indent
import scala.math.BigDecimal.RoundingMode._
import scala.collection.mutable

/** Intermediate Representation */
abstract class FirrtlNode {
  def serialize: String
}

abstract class Info extends FirrtlNode {
  // default implementation
  def serialize: String = this.toString
  def ++(that: Info): Info
}
case object NoInfo extends Info {
  override def toString: String = ""
  def ++(that: Info): Info = that
}
case class FileInfo(info: StringLit) extends Info {
  override def toString: String = " @[" + info.serialize + "]"
  //scalastyle:off method.name
  def ++(that: Info): Info = if (that == NoInfo) this else MultiInfo(Seq(this, that))
}
case class MultiInfo(infos: Seq[Info]) extends Info {
  private def collectStringLits(info: Info): Seq[StringLit] = info match {
    case FileInfo(lit) => Seq(lit)
    case MultiInfo(seq) => seq flatMap collectStringLits
    case NoInfo => Seq.empty
  }
  override def toString: String = {
    val parts = collectStringLits(this)
    if (parts.nonEmpty) parts.map(_.serialize).mkString(" @[", " ", "]")
    else ""
  }
  //scalastyle:off method.name
  def ++(that: Info): Info = if (that == NoInfo) this else MultiInfo(infos :+ that)
}
object MultiInfo {
  def apply(infos: Info*) = {
    val infosx = infos.filterNot(_ == NoInfo)
    infosx.size match {
      case 0 => NoInfo
      case 1 => infosx.head
      case _ => new MultiInfo(infosx)
    }
  }
}

trait HasName {
  val name: String
}
trait HasInfo {
  val info: Info
}
trait IsDeclaration extends HasName with HasInfo

case class StringLit(string: String) extends FirrtlNode {
  /** Returns an escaped and quoted String */
  def escape: String = {
    import scala.reflect.runtime.universe._
    Literal(Constant(string)).toString
  }
  def serialize: String = {
    val str = escape
    str.slice(1, str.size - 1)
  }
  /** Format the string for Verilog */
  def verilogFormat: StringLit = {
    StringLit(string.replaceAll("%x", "%h"))
  }
  /** Returns an escaped and quoted String */
  def verilogEscape: String = {
    // normalize to turn things like ö into o
    import java.text.Normalizer
    val normalized = Normalizer.normalize(string, Normalizer.Form.NFD)
    val ascii = normalized flatMap StringLit.toASCII
    ascii.mkString("\"", "", "\"")
  }
}
object StringLit {
  /** Maps characters to ASCII for Verilog emission */
  private def toASCII(char: Char): List[Char] = char match {
    case nonASCII if !nonASCII.isValidByte => List('?')
    case '"' => List('\\', '"')
    case '\\' => List('\\', '\\')
    case c if c >= ' ' && c <= '~' => List(c)
    case '\n' => List('\\', 'n')
    case '\t' => List('\\', 't')
    case _ => List('?')
  }

  /** Create a StringLit from a raw parsed String */
  def unescape(raw: String): StringLit = {
    val str = StringContext.processEscapes(raw)
    StringLit(str)
  }
}

/** Primitive Operation
  *
  * See [[PrimOps]]
  */
abstract class PrimOp extends FirrtlNode {
  def serialize: String = this.toString
}

abstract class Expression extends FirrtlNode {
  def tpe: Type
  def mapExpr(f: Expression => Expression): Expression
  def mapType(f: Type => Type): Expression
  def mapWidth(f: Width => Width): Expression
}
case class Reference(name: String, tpe: Type) extends Expression with HasName {
  def serialize: String = name
  def mapExpr(f: Expression => Expression): Expression = this
  def mapType(f: Type => Type): Expression = this.copy(tpe = f(tpe))
  def mapWidth(f: Width => Width): Expression = this
}
case class SubField(expr: Expression, name: String, tpe: Type) extends Expression with HasName {
  def serialize: String = s"${expr.serialize}.$name"
  def mapExpr(f: Expression => Expression): Expression = this.copy(expr = f(expr))
  def mapType(f: Type => Type): Expression = this.copy(tpe = f(tpe))
  def mapWidth(f: Width => Width): Expression = this
}
case class SubIndex(expr: Expression, value: Int, tpe: Type) extends Expression {
  def serialize: String = s"${expr.serialize}[$value]"
  def mapExpr(f: Expression => Expression): Expression = this.copy(expr = f(expr))
  def mapType(f: Type => Type): Expression = this.copy(tpe = f(tpe))
  def mapWidth(f: Width => Width): Expression = this
}
case class SubAccess(expr: Expression, index: Expression, tpe: Type) extends Expression {
  def serialize: String = s"${expr.serialize}[${index.serialize}]"
  def mapExpr(f: Expression => Expression): Expression =
    this.copy(expr = f(expr), index = f(index))
  def mapType(f: Type => Type): Expression = this.copy(tpe = f(tpe))
  def mapWidth(f: Width => Width): Expression = this
}
case class Mux(cond: Expression, tval: Expression, fval: Expression, tpe: Type) extends Expression {
  def serialize: String = s"mux(${cond.serialize}, ${tval.serialize}, ${fval.serialize})"
  def mapExpr(f: Expression => Expression): Expression = Mux(f(cond), f(tval), f(fval), tpe)
  def mapType(f: Type => Type): Expression = this.copy(tpe = f(tpe))
  def mapWidth(f: Width => Width): Expression = this
}
case class ValidIf(cond: Expression, value: Expression, tpe: Type) extends Expression {
  def serialize: String = s"validif(${cond.serialize}, ${value.serialize})"
  def mapExpr(f: Expression => Expression): Expression = ValidIf(f(cond), f(value), tpe)
  def mapType(f: Type => Type): Expression = this.copy(tpe = f(tpe))
  def mapWidth(f: Width => Width): Expression = this
}
abstract class Literal extends Expression {
  val value: BigInt
  val width: Width
}
case class UIntLiteral(value: BigInt, width: Width) extends Literal {
  def tpe = UIntType(width)
  def serialize = s"""UInt${width.serialize}("h""" + value.toString(16)+ """")"""
  def mapExpr(f: Expression => Expression): Expression = this
  def mapType(f: Type => Type): Expression = this
  def mapWidth(f: Width => Width): Expression = UIntLiteral(value, f(width))
}
object UIntLiteral {
  def minWidth(value: BigInt): Width = IntWidth(math.max(value.bitLength, 1))
  def apply(value: BigInt): UIntLiteral = new UIntLiteral(value, minWidth(value))
}
case class SIntLiteral(value: BigInt, width: Width) extends Literal {
  def tpe = SIntType(width)
  def serialize = s"""SInt${width.serialize}("h""" + value.toString(16)+ """")"""
  def mapExpr(f: Expression => Expression): Expression = this
  def mapType(f: Type => Type): Expression = this
  def mapWidth(f: Width => Width): Expression = SIntLiteral(value, f(width))
}
object SIntLiteral {
  def minWidth(value: BigInt): Width = IntWidth(value.bitLength + 1)
  def apply(value: BigInt): SIntLiteral = new SIntLiteral(value, minWidth(value))
}
case class FixedLiteral(value: BigInt, width: Width, point: Width) extends Literal {
  def tpe = FixedType(width, point)
  def serialize = {
    val pstring = if(point == UnknownWidth) "" else s"<${point.serialize}>"
    s"""Fixed${width.serialize}$pstring("h${value.toString(16)}")"""
  }
  def mapExpr(f: Expression => Expression): Expression = this
  def mapType(f: Type => Type): Expression = this
  def mapWidth(f: Width => Width): Expression = FixedLiteral(value, f(width), f(point))
}
case class DoPrim(op: PrimOp, args: Seq[Expression], consts: Seq[BigInt], tpe: Type) extends Expression {
  def serialize: String = op.serialize + "(" +
    (args.map(_.serialize) ++ consts.map(_.toString)).mkString(", ") + ")"
  def mapExpr(f: Expression => Expression): Expression = this.copy(args = args map f)
  def mapType(f: Type => Type): Expression = this.copy(tpe = f(tpe))
  def mapWidth(f: Width => Width): Expression = this
}

abstract class Statement extends FirrtlNode {
  def mapStmt(f: Statement => Statement): Statement
  def mapExpr(f: Expression => Expression): Statement
  def mapType(f: Type => Type): Statement
  def mapString(f: String => String): Statement
  def mapInfo(f: Info => Info): Statement
}
case class DefWire(info: Info, name: String, tpe: Type) extends Statement with IsDeclaration {
  def serialize: String = s"wire $name : ${tpe.serialize}" + info.serialize
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = this
  def mapType(f: Type => Type): Statement = DefWire(info, name, f(tpe))
  def mapString(f: String => String): Statement = DefWire(info, f(name), tpe)
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case class DefRegister(
    info: Info,
    name: String,
    tpe: Type,
    clock: Expression,
    reset: Expression,
    init: Expression) extends Statement with IsDeclaration {
  def serialize: String =
    s"reg $name : ${tpe.serialize}, ${clock.serialize} with :" +
    indent("\n" + s"reset => (${reset.serialize}, ${init.serialize})" + info.serialize)
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement =
    DefRegister(info, name, tpe, f(clock), f(reset), f(init))
  def mapType(f: Type => Type): Statement = this.copy(tpe = f(tpe))
  def mapString(f: String => String): Statement = this.copy(name = f(name))
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))

}
case class DefInstance(info: Info, name: String, module: String) extends Statement with IsDeclaration {
  def serialize: String = s"inst $name of $module" + info.serialize
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = this
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = DefInstance(info, f(name), module)
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case class DefMemory(
    info: Info,
    name: String,
    dataType: Type,
    depth: Int,
    writeLatency: Int,
    readLatency: Int,
    readers: Seq[String],
    writers: Seq[String],
    readwriters: Seq[String],
    // TODO: handle read-under-write
    readUnderWrite: Option[String] = None) extends Statement with IsDeclaration {
  def serialize: String =
    s"mem $name :" + info.serialize +
    indent(
      (Seq("\ndata-type => " + dataType.serialize,
          "depth => " + depth,
          "read-latency => " + readLatency,
          "write-latency => " + writeLatency) ++
          (readers map ("reader => " + _)) ++
          (writers map ("writer => " + _)) ++
          (readwriters map ("readwriter => " + _)) ++
       Seq("read-under-write => undefined")) mkString "\n")
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = this
  def mapType(f: Type => Type): Statement = this.copy(dataType = f(dataType))
  def mapString(f: String => String): Statement = this.copy(name = f(name))
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case class DefNode(info: Info, name: String, value: Expression) extends Statement with IsDeclaration {
  def serialize: String = s"node $name = ${value.serialize}" + info.serialize
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = DefNode(info, name, f(value))
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = DefNode(info, f(name), value)
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case class Conditionally(
    info: Info,
    pred: Expression,
    conseq: Statement,
    alt: Statement) extends Statement with HasInfo {
  def serialize: String =
    s"when ${pred.serialize} :" + info.serialize +
    indent("\n" + conseq.serialize) +
    (if (alt == EmptyStmt) ""
    else "\nelse :" + indent("\n" + alt.serialize))
  def mapStmt(f: Statement => Statement): Statement = Conditionally(info, pred, f(conseq), f(alt))
  def mapExpr(f: Expression => Expression): Statement = Conditionally(info, f(pred), conseq, alt)
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = this
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case class Block(stmts: Seq[Statement]) extends Statement {
  def serialize: String = stmts map (_.serialize) mkString "\n"
  def mapStmt(f: Statement => Statement): Statement = Block(stmts map f)
  def mapExpr(f: Expression => Expression): Statement = this
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = this
  def mapInfo(f: Info => Info): Statement = this
}
case class PartialConnect(info: Info, loc: Expression, expr: Expression) extends Statement with HasInfo {
  def serialize: String =  s"${loc.serialize} <- ${expr.serialize}" + info.serialize
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = PartialConnect(info, f(loc), f(expr))
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = this
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case class Connect(info: Info, loc: Expression, expr: Expression) extends Statement with HasInfo {
  def serialize: String =  s"${loc.serialize} <= ${expr.serialize}" + info.serialize
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = Connect(info, f(loc), f(expr))
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = this
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case class IsInvalid(info: Info, expr: Expression) extends Statement with HasInfo {
  def serialize: String =  s"${expr.serialize} is invalid" + info.serialize
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = IsInvalid(info, f(expr))
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = this
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case class Attach(info: Info, exprs: Seq[Expression]) extends Statement with HasInfo {
  def serialize: String = "attach " + exprs.map(_.serialize).mkString("(", ", ", ")")
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = Attach(info, exprs map f)
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = this
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case class Stop(info: Info, ret: Int, clk: Expression, en: Expression) extends Statement with HasInfo {
  def serialize: String = s"stop(${clk.serialize}, ${en.serialize}, $ret)" + info.serialize
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = Stop(info, ret, f(clk), f(en))
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = this
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case class Print(
    info: Info,
    string: StringLit,
    args: Seq[Expression],
    clk: Expression,
    en: Expression) extends Statement with HasInfo {
  def serialize: String = {
    val strs = Seq(clk.serialize, en.serialize, string.escape) ++
               (args map (_.serialize))
    "printf(" + (strs mkString ", ") + ")" + info.serialize
  }
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = Print(info, string, args map f, f(clk), f(en))
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = this
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
}
case object EmptyStmt extends Statement {
  def serialize: String = "skip"
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = this
  def mapType(f: Type => Type): Statement = this
  def mapString(f: String => String): Statement = this
  def mapInfo(f: Info => Info): Statement = this
}

abstract class Width extends FirrtlNode {
  def +(x: Width): Width = (this, x) match {
    case (a: IntWidth, b: IntWidth) => IntWidth(a.width + b.width)
    case _ => UnknownWidth
  }
  def -(x: Width): Width = (this, x) match {
    case (a: IntWidth, b: IntWidth) => IntWidth(a.width - b.width)
    case _ => UnknownWidth
  }
  def max(x: Width): Width = (this, x) match {
    case (a: IntWidth, b: IntWidth) => IntWidth(a.width max b.width)
    case _ => UnknownWidth
  }
  def min(x: Width): Width = (this, x) match {
    case (a: IntWidth, b: IntWidth) => IntWidth(a.width min b.width)
    case _ => UnknownWidth
  }
}
/** Positive Integer Bit Width of a [[GroundType]] */
object IntWidth {
  private val maxCached = 1024
  private val cache = new Array[IntWidth](maxCached + 1)
  def apply(width: BigInt): IntWidth = {
    if (0 <= width && width <= maxCached) {
      val i = width.toInt
      var w = cache(i)
      if (w eq null) {
        w = new IntWidth(width)
        cache(i) = w
      }
      w
    } else new IntWidth(width)
  }
  // For pattern matching
  def unapply(w: IntWidth): Option[BigInt] = Some(w.width)
}
class IntWidth(val width: BigInt) extends Width with Product {
  def serialize: String = s"<$width>"
  override def equals(that: Any) = that match {
    case w: IntWidth => width == w.width
    case _ => false
  }
  override def hashCode = width.toInt
  override def productPrefix = "IntWidth"
  override def toString = s"$productPrefix($width)"
  def copy(width: BigInt = width) = IntWidth(width)
  def canEqual(that: Any) = that.isInstanceOf[Width]
  def productArity = 1
  def productElement(int: Int) = int match {
    case 0 => width
    case _ => throw new IndexOutOfBoundsException
  }
}
case object UnknownWidth extends Width {
  def serialize: String = ""
}

/** Orientation of [[Field]] */
abstract class Orientation extends FirrtlNode
case object Default extends Orientation {
  def serialize: String = ""
}
case object Flip extends Orientation {
  def serialize: String = "flip "
}

/** Field of [[BundleType]] */
case class Field(name: String, flip: Orientation, tpe: Type) extends FirrtlNode with HasName {
  def serialize: String = flip.serialize + name + " : " + tpe.serialize
}

/** Bounds of [[IntervalType]] */

trait IsConstrainable[T] {
  def optimize(): T
  def map(f: T=>T): T
  def serialize: String
}
trait IsVar {
  def name: String
}
trait IsMax[T] {
  def children: Seq[T]
}
trait IsMin[T] {
  def children: Seq[T]
}
trait IsAdd[T] {
  def children: Seq[T]
}
trait IsMul[T] {
  def children: Seq[T]
}
trait IsNeg[T] {
  def children: Seq[T]
}
trait IsVal {
  def value: BigDecimal
}
trait Bound extends IsConstrainable[Bound] {
  def serialize: String
  def map(f: Bound=>Bound): Bound
  def children: Seq[Bound] = {
    val kids = mutable.ArrayBuffer[Bound]()
    def f(b: Bound): Bound = {
      kids += b
      b
    }
    map(f)
    kids.toSeq
  }
  def optimize(): Bound = map(_.optimize()).reduce()
  def gen(bounds: Seq[Bound]): Bound
  def op(b1: KnownBound, b2: KnownBound): Bound
  def identity: Option[Int]
  def reduce(): Bound = {
    val newBounds = children.flatMap{ 
      _ match {
        case a if a.getClass == this.getClass => a.children
        case x => Seq(x)
      }
    }
    val groups = newBounds.groupBy {
      case x: KnownBound => "known"
      case x: MaxBound   => "max"
      case x: MinBound   => "min"
      case x             => "other"
    }
    def genericOp(b1: Bound, b2: Bound): Bound = (b1, b2) match {
      case (k1: KnownBound, k2: KnownBound) => op(k1, k2)
      case _ => sys.error("Shouldn't be here")
    }
    val known = groups.get("known") match {
      case None => Nil
      case Some(seq) => seq.reduce(genericOp) match {
        case k: KnownBound if Some(k.value) == identity => Nil //TODO add multiply by zero
        case k => Seq(k)
      }
    }
    val max = groups.get("max") match {
      case None => Nil
      case Some(seq) => seq
    }
    val min = groups.get("min") match {
      case None => Nil
      case Some(seq) => seq
    }
    val others = groups.get("other") match {
      case None => Nil
      case Some(seq) => seq
    }
    (max, min) match {
      case (Nil, Nil) if known.size + others.size == 1 => (known ++ others).head
      case (Nil, Nil) => gen(known ++ others)
      case (Seq(x), Nil) => MaxBound(x.children.map { c => gen(Seq(c) ++ known ++ others).reduce() }:_*)
      case (Nil, Seq(x)) => MinBound(x.children.map { c => gen(Seq(c) ++ known ++ others).reduce() }:_*)
      case (_, _) => gen(known ++ others ++ max ++ min)
    }
  }
  //def /(that: Bound): Bound = (this, that) match {
  //  case (Open(x),   Open(y))   if y == 0 => Open(x)
  //  case (Open(x),   Open(y))             => Open(x / y)
  //  case (Closed(x), Open(y))   if y == 0 => Open(x)
  //  case (Closed(x), Open(y))             => Open(x / y)
  //  case (Open(x),   Closed(y)) if y == 0 => Open(x)
  //  case (Open(x),   Closed(y))           => Open(x / y)
  //  case (Closed(x), Closed(y)) if y == 0 => Closed(x)
  //  case (Closed(x), Closed(y))           => Closed(x / y)
  //}
  //def mod(that: Bound): Bound = that match {
  //  case Open(x)   if x == 0 => Open(x)
  //  case Open(x)             => Open(value % x)
  //  case Closed(x) if x == 0 => Closed(x)
  //  case Closed(x)           => Closed(value % x)
  //}
}

case class AddBound(bounds: Bound*) extends Bound with IsAdd[Bound] {
  val identity = Some(0)
  def gen(bounds: Seq[Bound]): Bound = AddBound(bounds:_*)
  def op(b1: KnownBound, b2: KnownBound): Bound = b1 + b2
  def serialize: String = "(" + bounds.map(_.serialize).mkString(" + ") + ")"
  def map(f: Bound=>Bound): Bound = AddBound(bounds.map(f):_*)
}
case class MulBound(bounds: Bound*) extends Bound with IsMul[Bound] {
  val identity = Some(1)
  def gen(bounds: Seq[Bound]): Bound = MulBound(bounds:_*)
  def op(b1: KnownBound, b2: KnownBound): Bound = b1 * b2
  def map(f: Bound=>Bound): Bound = MulBound(bounds.map(f):_*)
  def serialize: String = "(" + bounds.map(_.serialize).mkString(" * ") + ")"
}
case class NegBound(bound: Bound) extends Bound with IsNeg[Bound] {
  def identity: Option[Int] = sys.error("Shouldn't be here")
  override def reduce(): Bound = bound match {
    case k: KnownBound => k.neg
    case AddBound(bounds) => AddBound(bounds.map { b => NegBound(b).reduce }).reduce
    case MulBound(bounds) => MulBound(bounds.map { b => NegBound(b).reduce }).reduce
    case NegBound(bound) => bound
    case MaxBound(bounds) => MinBound(bounds.map {b => NegBound(b).reduce }).reduce
    case MinBound(bounds) => MaxBound(bounds.map {b => NegBound(b).reduce }).reduce
  }
  def gen(bounds: Seq[Bound]): Bound = MulBound(bounds:_*)
  def op(b1: KnownBound, b2: KnownBound): Bound = sys.error("Shouldn't be here")
  def map(f: Bound=>Bound): Bound = NegBound(f(bound))
  def serialize: String = "(-" + bound.serialize + ")"
}
case class MaxBound(bounds: Bound*) extends Bound with IsMax[Bound] {
  def identity: Option[Int] = None
  def gen(bounds: Seq[Bound]): Bound = MaxBound(bounds:_*)
  def op(b1: KnownBound, b2: KnownBound): Bound = b1 max b2
  //override def reduce() = {
  //  val newBounds = bounds.foldLeft(Nil: Seq[Bound]){ (acc, b) =>
  //    b match {
  //      case a: MaxBound => acc ++ a.bounds
  //      case x => acc ++ Seq(x)
  //    }
  //  }
  //  MaxBound(newBounds.distinct:_*)
  //}
  def serialize: String = "max(" + bounds.map(_.serialize).mkString(", ") + ")"
  def map(f: Bound=>Bound): Bound = MaxBound(bounds.map(f):_*)
}
case class MinBound(bounds: Bound*) extends Bound with IsMin[Bound] {
  def identity: Option[Int] = sys.error("Shouldn't be here")
  def gen(bounds: Seq[Bound]): Bound = MinBound(bounds:_*)
  def op(b1: KnownBound, b2: KnownBound): Bound = b1 min b2
  def serialize: String = "min(" + bounds.map(_.serialize).mkString(", ") + ")"
  def map(f: Bound=>Bound): Bound = MinBound(bounds.map(f):_*)
}
case object UnknownBound extends Bound {
  def identity: Option[Int] = sys.error("Shouldn't be here")
  def gen(bounds: Seq[Bound]): Bound = sys.error("Shouldn't be here")
  def op(b1: KnownBound, b2: KnownBound): Bound =  sys.error("Shouldn't be here")
  override def reduce() = this
  def serialize: String = "?"
  def map(f: Bound=>Bound): Bound = this
}
case class VarBound(name: String) extends Bound with IsVar {
  def identity: Option[Int] = sys.error("Shouldn't be here")
  def gen(bounds: Seq[Bound]): Bound = sys.error("Shouldn't be here")
  def op(b1: KnownBound, b2: KnownBound): Bound =  sys.error("Shouldn't be here")
  override def reduce() = this
  def serialize: String = name
  def map(f: Bound=>Bound): Bound = this
}
sealed trait KnownBound extends Bound with IsVal {
  def identity: Option[Int] = sys.error("Shouldn't be here")
  val value: BigDecimal
  def +(that: KnownBound): KnownBound
  //def -(that: KnownBound): KnownBound
  def *(that: KnownBound): KnownBound
  def max(that: KnownBound): KnownBound
  def min(that: KnownBound): KnownBound
  def neg: KnownBound

  // Unnecessary members
  def gen(bounds: Seq[Bound]): Bound = sys.error("Shouldn't be here")
  def op(b1: KnownBound, b2: KnownBound): Bound =  sys.error("Shouldn't be here")
  override def reduce() = this
  def map(f: Bound=>Bound): Bound = this
}
object KnownBound {
  def unapply(b: Bound): Option[BigDecimal] = b match {
    case k: KnownBound => Some(k.value)
    case _ => None
  }
}
case class Open(value: BigDecimal) extends KnownBound {
  def serialize = s"o($value)"
  def +(that: KnownBound): KnownBound = Open(value + that.value)
  def *(that: KnownBound): KnownBound = Open(value * that.value)
  def min(that: KnownBound): KnownBound = if(value < that.value) this else that
  def max(that: KnownBound): KnownBound = if(value > that.value) this else that
  def neg: KnownBound = Open(-value)
}
case class Closed(value: BigDecimal) extends KnownBound {
  def serialize = s"c($value)"
  def +(that: KnownBound): KnownBound = that match {
    case Open(x) => Open(value + x)
    case Closed(x) => Closed(value + x)
  }
  def *(that: KnownBound): KnownBound = that match {
    case Open(x) => Open(value * x)
    case Closed(x) => Closed(value * x)
  }
  def min(that: KnownBound): KnownBound = if(value <= that.value) this else that
  def max(that: KnownBound): KnownBound = if(value >= that.value) this else that
  def neg: KnownBound = Closed(-value)
}

/** Types of [[FirrtlNode]] */
abstract class Type extends FirrtlNode {
  def mapType(f: Type => Type): Type
  def mapWidth(f: Width => Width): Type
}
abstract class GroundType extends Type {
  val width: Width
  def mapType(f: Type => Type): Type = this
}
object GroundType {
  def unapply(ground: GroundType): Option[Width] = Some(ground.width)
}
abstract class AggregateType extends Type {
  def mapWidth(f: Width => Width): Type = this
}
case class UIntType(width: Width) extends GroundType {
  def serialize: String = "UInt" + width.serialize
  def mapWidth(f: Width => Width): Type = UIntType(f(width))
}
case class SIntType(width: Width) extends GroundType {
  def serialize: String = "SInt" + width.serialize
  def mapWidth(f: Width => Width): Type = SIntType(f(width))
}
case class FixedType(width: Width, point: Width) extends GroundType {
  override def serialize: String = {
    val pstring = if(point == UnknownWidth) "" else s"<${point.serialize}>"
    s"Fixed${width.serialize}$pstring"
  }
  def mapWidth(f: Width => Width): Type = FixedType(f(width), f(point))
}
case class IntervalType(lower: Bound, upper: Bound, point: Width) extends GroundType {
  override def serialize: String = {
    val lowerString = lower match {
      case Open(l)      => s"($l, "
      case Closed(l)    => s"[$l, "
      case UnknownBound => s"[?, "
      case VarBound(n)  => s"[?, "
    }
    val upperString = upper match {
      case Open(u)      => s"$u)"
      case Closed(u)    => s"$u]"
      case UnknownBound => s"?]"
      case VarBound(n)  => s"?]"
    }
    val bounds = (lower, upper) match {
      case (k1: KnownBound, k2: KnownBound) => lowerString + upperString
      case _ => ""
    }
    val pointString = point match {
      case UnknownWidth => ""
      case IntWidth(i)  => "." + i.toString
    }
    "Interval" + bounds + pointString
  }
  private def calcWidth(value: BigInt): Int = value match {
    case v if(v == 0) => 0
    case v if(v > 0) => firrtl.Utils.ceilLog2(v) + 1
    case v if(v == -1) => 1
    case v if(v < -1) => firrtl.Utils.ceilLog2(-v - 1) + 1 //e.g. v = -2 -> 1
  }
  //TODO(azidar): move somewhere else?
  val width: Width = (point, lower, upper) match {
    case (IntWidth(i), l: KnownBound, u: KnownBound) =>
      def resize(value: BigDecimal): BigDecimal = value * Math.pow(2, i.toInt)
      val resizedMin = l match {
        case Open(x) => resize(x) match {
          case v if v.scale == 0 => v + 1
          case v => v.setScale(0, UP)
        }
        case Closed(x) => resize(x) match {
          case v if v.scale == 0 => v
          case v => v.setScale(0, DOWN)
        }
      }
      val resizedMax = u match {
        case Open(x) => resize(x) match {
          case v if v.scale == 0 => v - 1
          case v => v.setScale(0, DOWN)
        }
        case Closed(x) => resize(x) match {
          case v if v.scale == 0 => v
          case v => v.setScale(0, UP)
        }
      }
      IntWidth(Math.max(calcWidth(resizedMin.toBigInt), calcWidth(resizedMax.toBigInt)))
    case _ => UnknownWidth
  }
  def mapWidth(f: Width => Width): Type = this.copy(point = f(point))
  //def range: Seq[BigDecimal] = {
  //  val prec = 1/Math.pow(2, point.get.toDouble)
  //  val minAdjusted = min match {
  //    case Open(a) => (a / prec) match {
  //      case x if x == 0 => x + prec // add precision for open min bound
  //      case x => x.setScale(0, DOWN) * prec
  //    }
  //    case Closed(a) => (a / prec).setScale(0, UP) * prec
  //  }
  //  val maxAdjusted = max match {
  //    case Open(a) => (a / prec) match {
  //      case x if x == 0 => x - prec // subtract precision for open max bound
  //      case x => x.setScale(0, DOWN) * prec
  //    }
  //    case Closed(a) => (a / prec).setScale(0, UP) * prec
  //  }
  //  Range.BigDecimal(minAdjusted, maxAdjusted, prec)
  //}
}
case class BundleType(fields: Seq[Field]) extends AggregateType {
  def serialize: String = "{ " + (fields map (_.serialize) mkString ", ") + "}"
  def mapType(f: Type => Type): Type =
    BundleType(fields map (x => x.copy(tpe = f(x.tpe))))
}
case class VectorType(tpe: Type, size: Int) extends AggregateType {
  def serialize: String = tpe.serialize + s"[$size]"
  def mapType(f: Type => Type): Type = this.copy(tpe = f(tpe))
}
case object ClockType extends GroundType {
  val width = IntWidth(1)
  def serialize: String = "Clock"
  def mapWidth(f: Width => Width): Type = this
}
case class AnalogType(width: Width) extends GroundType {
  def serialize: String = "Analog" + width.serialize
  def mapWidth(f: Width => Width): Type = AnalogType(f(width))
}
case object UnknownType extends Type {
  def serialize: String = "?"
  def mapType(f: Type => Type): Type = this
  def mapWidth(f: Width => Width): Type = this
}

/** [[Port]] Direction */
abstract class Direction extends FirrtlNode
case object Input extends Direction {
  def serialize: String = "input"
}
case object Output extends Direction {
  def serialize: String = "output"
}

/** [[DefModule]] Port */
case class Port(
    info: Info,
    name: String,
    direction: Direction,
    tpe: Type) extends FirrtlNode with IsDeclaration {
  def serialize: String = s"${direction.serialize} $name : ${tpe.serialize}" + info.serialize
  def mapType(f: Type => Type): Port = Port(info, name, direction, f(tpe))
}

/** Parameters for external modules */
sealed abstract class Param extends FirrtlNode {
  def name: String
  def serialize: String = s"parameter $name = "
}
/** Integer (of any width) Parameter */
case class IntParam(name: String, value: BigInt) extends Param {
  override def serialize: String = super.serialize + value
}
/** IEEE Double Precision Parameter (for Verilog real) */
case class DoubleParam(name: String, value: Double) extends Param {
  override def serialize: String = super.serialize + value
}
/** String Parameter */
case class StringParam(name: String, value: StringLit) extends Param {
  override def serialize: String = super.serialize + value.escape
}
/** Raw String Parameter
  * Useful for Verilog type parameters
  * @note Firrtl doesn't guarantee anything about this String being legal in any backend
  */
case class RawStringParam(name: String, value: String) extends Param {
  override def serialize: String = super.serialize + s"'${value.replace("'", "\\'")}'"
}

/** Base class for modules */
abstract class DefModule extends FirrtlNode with IsDeclaration {
  val info : Info
  val name : String
  val ports : Seq[Port]
  protected def serializeHeader(tpe: String): String =
    s"$tpe $name :${info.serialize}${indent(ports.map("\n" + _.serialize).mkString)}\n"
  def mapStmt(f: Statement => Statement): DefModule
  def mapPort(f: Port => Port): DefModule
  def mapString(f: String => String): DefModule
  def mapInfo(f: Info => Info): DefModule
}
/** Internal Module
  *
  * An instantiable hardware block
  */
case class Module(info: Info, name: String, ports: Seq[Port], body: Statement) extends DefModule {
  def serialize: String = serializeHeader("module") + indent("\n" + body.serialize)
  def mapStmt(f: Statement => Statement): DefModule = this.copy(body = f(body))
  def mapPort(f: Port => Port): DefModule = this.copy(ports = ports map f)
  def mapString(f: String => String): DefModule = this.copy(name = f(name))
  def mapInfo(f: Info => Info): DefModule = this.copy(f(info))
}
/** External Module
  *
  * Generally used for Verilog black boxes
  * @param defname Defined name of the external module (ie. the name Firrtl will emit)
  */
case class ExtModule(
    info: Info,
    name: String,
    ports: Seq[Port],
    defname: String,
    params: Seq[Param]) extends DefModule {
  def serialize: String = serializeHeader("extmodule") +
    indent(s"\ndefname = $defname\n" + params.map(_.serialize).mkString("\n"))
  def mapStmt(f: Statement => Statement): DefModule = this
  def mapPort(f: Port => Port): DefModule = this.copy(ports = ports map f)
  def mapString(f: String => String): DefModule = this.copy(name = f(name))
  def mapInfo(f: Info => Info): DefModule = this.copy(f(info))
}

case class Circuit(info: Info, modules: Seq[DefModule], main: String) extends FirrtlNode with HasInfo {
  def serialize: String =
    s"circuit $main :" + info.serialize +
    (modules map ("\n" + _.serialize) map indent mkString "\n") + "\n"
  def mapModule(f: DefModule => DefModule): Circuit = this.copy(modules = modules map f)
  def mapString(f: String => String): Circuit = this.copy(main = f(main))
  def mapInfo(f: Info => Info): Circuit = this.copy(f(info))
}
