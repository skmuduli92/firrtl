// See LICENSE for license details.

package firrtl.options

import firrtl.AnnotationSeq
import firrtl.graph.{DiGraph, CyclicException}

import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap, LinkedHashSet, Queue}

/** An exception arising from an in a [[DependencyManager]] */
case class DependencyManagerException(message: String, cause: Throwable = null) extends RuntimeException(message, cause)

/** A [[TransformLike]] that resolves a linear ordering of dependencies based on requirements.
  * @tparam A the type over which this transforms
  * @tparam B the type of the [[TransformLike]]
  */
trait DependencyManager[A, B <: TransformLike[A] with DependencyAPI[B]] extends TransformLike[A] with DependencyAPI[B] {

  /** The set of transforms that should be run */
  def targets: Set[Class[B]]
  private lazy val _targets: LinkedHashSet[Class[B]] = new LinkedHashSet[Class[B]]() ++ targets

  /** The set of transforms that have been run */
  def currentState: Set[Class[B]]
  private lazy val _currentState: LinkedHashSet[Class[B]] = new LinkedHashSet[Class[B]]() ++ currentState

  /** Existing transform objects that have already been constructed */
  def knownObjects: Set[B]

  /** A sequence of wrappers to apply to the resulting [[TransformLike]] sequence. This can be used to, e.g., add
    * automated pre-processing and post-processing.
    */
  def wrappers: Seq[(B) => B] = Seq.empty

  /** Store of conversions between classes and objects. Objects that do not exist in the map will be lazily constructed.
    */
  protected lazy val classToObject: LinkedHashMap[Class[B], B] = {
    val init = LinkedHashMap[Class[B], B](knownObjects.map(x => x.getClass.asInstanceOf[Class[B]] -> x).toSeq: _*)
    (_targets ++ _currentState)
      .filter(!init.contains(_))
      .map(x => init(x) = safeConstruct(x))
    init
  }

  /** A method that will create a copy of this [[DependencyManager]], but with different requirements. This is used by
    * this [[DependencyManager]] to solve sub-problems arising from invalidations.
    */
  protected def copy(
    targets: Set[Class[B]],
    currentState: Set[Class[B]],
    knownObjects: Set[B] = classToObject.values.toSet): B

  /** Implicit conversion from Class[B] to B */
  private implicit def cToO(c: Class[B]): B = classToObject.getOrElseUpdate(c, safeConstruct(c))

  /** Implicit conversion from B to Class[B] */
  private implicit def oToC(b: B): Class[B] = b.getClass.asInstanceOf[Class[B]]

  /** Modified breadth-first search that supports multiple starting nodes and a custom extractor that can be used to
    * generate/filter the edges to explore. Additionally, this will include edges to previously discovered nodes.
    */
  private def bfs( start: LinkedHashSet[Class[B]],
                   blacklist: LinkedHashSet[Class[B]],
                   extractor: B => Set[Class[B]] ): LinkedHashMap[B, LinkedHashSet[B]] = {

    val (queue, edges) = {
      val a: Queue[Class[B]]                    = Queue(start.toSeq:_*)
      val b: LinkedHashMap[B, LinkedHashSet[B]] = LinkedHashMap[B, LinkedHashSet[B]](
        start.map((cToO(_) -> LinkedHashSet[B]())).toSeq:_*)
      (a, b)
    }

    while (queue.nonEmpty) {
      val u: Class[B] = queue.dequeue
      for (v: Class[B] <- extractor(classToObject(u))) {
        if (!blacklist.contains(v) && !edges.contains(v)) {
          queue.enqueue(v)
        }
        if (!edges.contains(v)) {
          val obj = cToO(v)
          edges(obj) = LinkedHashSet.empty
          classToObject += (v -> obj)
        }
        edges(classToObject(u)) = edges(classToObject(u)) + classToObject(v)
      }
    }

    edges
  }

  /** Pull in all registered [[TransformLike]] once [[TransformLike]] registration is integrated
    * @todo implement this
    */
  private lazy val registeredTransforms: Set[B] = Set.empty

  /** A directed graph consisting of prerequisite edges */
  private lazy val prerequisiteGraph: DiGraph[B] = {
    val edges = bfs(
      start = _targets -- _currentState,
      blacklist = _currentState,
      extractor = (p: B) => p.prerequisites -- _currentState)
    DiGraph(edges)
  }

  /** A directed graph consisting of prerequisites derived from only those transforms which are supposed to run. This
    * pulls in dependents for transforms which are not in the target set.
    */
  private lazy val dependentsGraph: DiGraph[B] = {
    val v = prerequisiteGraph.getVertices
    DiGraph(v.map(vv => vv -> (vv.dependents.map(cToO) & v)).toMap).reverse
  }

  /** A directed graph consisting of prerequisites derived from ALL targets. This is necessary for defining targets for
    * [[DependencyManager]] sub-problems.
    */
  private lazy val otherDependents: DiGraph[B] = {
    val edges = _targets
      .map(classToObject)
      .map( a => a -> prerequisiteGraph.getVertices.filter(a.dependents(_)) )
      .toMap
    DiGraph(edges).reverse
  }

  /** A directed graph consisting of all prerequisites, including prerequisites derived from dependents */
  lazy val dependencyGraph: DiGraph[B] = prerequisiteGraph + dependentsGraph

  /** A directed graph consisting of invalidation edges */
  lazy val invalidateGraph: DiGraph[B] = {
    val v = dependencyGraph.getVertices
    DiGraph(
      bfs(
        start = _targets -- _currentState,
        blacklist = _currentState,
        extractor = (p: B) => v.filter(p.invalidates).map(_.asClass).toSet))
      .reverse
  }

  /** Wrap a possible [[CyclicException]] thrown by a thunk in a [[DependencyManagerException]] */
  private def cyclePossible[A](a: String, diGraph: DiGraph[_])(thunk: => A): A = try { thunk } catch {
    case e: CyclicException =>
      throw new DependencyManagerException(
        s"""|No transform ordering possible due to cyclic dependency in $a with cycles:
            |${diGraph.findSCCs.filter(_.size > 1).mkString("    - ", "\n    - ", "")}""".stripMargin, e)
  }

  /** Wrap an [[IllegalAccessException]] due to attempted object construction in a [[DependencyManagerException]] */
  private def safeConstruct[A](a: Class[A]): A = try { a.newInstance } catch {
    case e: IllegalAccessException => throw new DependencyManagerException(
      s"Failed to construct '$a'! (Did you try to construct an object?)", e)
    case e: InstantiationException => throw new DependencyManagerException(
      s"Failed to construct '$a'! (Did you try to construct an inner class or a class with parameters?)", e)
  }

  /** An ordering of [[TransformLike]]s that causes the requested [[DependencyManager.targets targets]] to be executed
    * starting from the [[DependencyManager.currentState currentState]]. This ordering respects prerequisites,
    * dependents, and invalidates of all constituent [[TransformLike]]s. This uses an algorithm that attempts to reduce
    * the number of re-lowerings due to invalidations. Re-lowerings are implemented as new [[DependencyManager]]s.
    * @throws DependencyManagerException if a cycle exists in either the [[DependencyManager.dependencyGraph
    * dependencyGraph]] or the [[DependencyManager.invalidateGraph invalidateGraph]].
    */
  lazy val transformOrder: Seq[B] = {

    /* Topologically sort the dependency graph using the invalidate graph topological sort as a seed. This has the effect of
     * reducing (perhaps minimizing?) the number of work re-lowerings.
     */
    val sorted = {
      val seed = cyclePossible("invalidates", invalidateGraph){ invalidateGraph.linearize }.reverse

      cyclePossible("prerequisites/dependents", dependencyGraph) {
        dependencyGraph
          .seededLinearize(Some(seed))
          .reverse
          .dropWhile(b => _currentState.contains(b))
      }
    }

    val (state, lowerers) = {
      /* [todo] Seq is inefficient here, but Array has ClassTag problems. Use something else? */
      val (s, l) = sorted.foldLeft((_currentState, Seq[B]())){ case ((state, out), in) =>
        /* The prerequisites are both prerequisites AND dependents */
        val prereqs = new LinkedHashSet() ++ in.prerequisites ++
          dependencyGraph.getEdges(in).toSet.map(oToC) ++
          otherDependents.getEdges(in).toSet.map(oToC)
        val missing = (prereqs -- state)
        val preprocessing: Option[B] = {
          if (missing.nonEmpty) { Some(this.copy(prereqs.toSet, state.toSet)) }
          else                  { None                                     }
        }
        ((state ++ missing + in).map(cToO).filterNot(in.invalidates).map(oToC), out ++ preprocessing :+ in)
      }
      val missing = (_targets -- s)
      val postprocessing: Option[B] = {
        if (missing.nonEmpty) { Some(this.copy(_targets.toSet, s.toSet)) }
        else                  { None                        }
      }

      (s ++ missing, l ++ postprocessing)
    }

    if (!_targets.subsetOf(state)) {
      throw new DependencyManagerException(
        s"The final state ($state) did not include the requested targets (${targets})!")
    }
    lowerers
  }

  /** A version of the [[DependencyManager.transformOrder transformOrder]] that flattens the transforms of any internal
    * [[DependencyManager]]s.
    */
  lazy val flattenedTransformOrder: Seq[B] = transformOrder.flatMap {
    case p: DependencyManager[A, B] => p.flattenedTransformOrder
    case p => Some(p)
  }

  final override def transform(annotations: A): A =
    flattenedTransformOrder
      .foldLeft(annotations){ case (a, p) =>
        wrappers
          .foldLeft(p){ case (pp, w) => w(pp) }
          .transform(a)
      }

  /** This colormap uses Colorbrewer's 4-class OrRd color scheme */
  protected val colormap = Seq("#fef0d9", "#fdcc8a", "#fc8d59", "#d7301f")

  /** Get a name of some [[TransformLike]] */
  private def transformName(transform: B, suffix: String = ""): String = {
    val a =
      transform
        .getClass
        .getSimpleName

    s""""$a$suffix""""
  }

  /** Convert all prerequisites, dependents, and invalidates to a Graphviz representation.
    * @param file the name of the output file
    */
  def dependenciesToGraphviz: String = {

    def toGraphviz(digraph: DiGraph[B], attributes: String = "", tab: String = "    "): Option[String] = {
      val edges =
        digraph
          .getEdgeMap
          .collect{ case (v, edges) if edges.nonEmpty => (v -> edges) }
          .map{ case (v, edges) =>
            s"""${transformName(v)} -> ${edges.map(e => transformName(e)).mkString("{ ", " ", " }")}""" }

      if (edges.isEmpty) { None } else {
        Some(
          s"""|  { $attributes
              |${edges.mkString(tab, "\n" + tab, "")}
              |  }""".stripMargin
        )
      }
    }

    val connections =
      Seq( (prerequisiteGraph, "edge []"),
           (dependentsGraph,   "edge []"),
           (invalidateGraph,   "edge [minlen=2,style=dashed,constraint=false]") )
        .flatMap{ case (a, b) => toGraphviz(a, b) }
        .mkString("\n")

    s"""|digraph DependencyManager {
        |  graph [rankdir=BT]
        |  node [fillcolor="${colormap(0)}",style=filled,shape=box]
        |$connections
        |}""".stripMargin
  }

  def transformOrderToGraphviz(colormap: Seq[String] = colormap): String = {

    def rotate[A](a: Seq[A]): Seq[A] = a match {
      case Nil => Nil
      case car :: cdr => cdr :+ car
      case car => car
    }

    val sorted = ArrayBuffer.empty[String]

    def rec(pm: DependencyManager[A, B], cm: Seq[String], tab: String = "", id: Int = 0): (String, Int) = {
      var offset = id

      val header = s"""|${tab}subgraph cluster_$id {
                       |$tab  label="target=${pm._targets}, state=${pm._currentState}"
                       |$tab  labeljust=r
                       |$tab  node [fillcolor="${cm.head}"]""".stripMargin

      val body = pm.transformOrder.map{
        case a: DependencyManager[A, B] =>
          val (str, d) = rec(a, rotate(cm), tab + "  ", offset + 1)
          offset = d
          str
        case a =>
          val name = s"""${transformName(a, "_" + id)}"""
          sorted += name
          s"""$tab  $name"""
      }.mkString("\n")

      (Seq(header, body, s"$tab}").mkString("\n"), offset)
    }

    s"""|digraph DependencyManagerTransformOrder {
        |  graph [rankdir=TB]
        |  node [style=filled,shape=box]
        |${rec(this, colormap, "  ")._1}
        |  ${sorted.mkString(" -> ")}
        |}""".stripMargin
  }

}

/** A [[Phase]] that will ensure that some other [[Phase]]s and their prerequisites are executed.
  *
  * This tries to determine a phase ordering such that an [[AnnotationSeq]] ''output'' is produced that has had all of
  * the requested [[Phase]] target transforms run without having them be invalidated.
  * @param targets the [[Phase]]s you want to run
  */
class PhaseManager(
  val targets: Set[Class[Phase]],
  val currentState: Set[Class[Phase]] = Set.empty,
  val knownObjects: Set[Phase] = Set.empty) extends Phase with DependencyManager[AnnotationSeq, Phase] {

  protected def copy(a: Set[Class[Phase]], b: Set[Class[Phase]], c: Set[Phase]) = new PhaseManager(a, b, c)

}
