// See LICENSE for license details.

package firrtl.stage.transforms

import firrtl.{CircuitState, Transform}
import firrtl.stage.TransformManager

class Compiler(
  targets: Set[Class[Transform]],
  currentState: Set[Class[Transform]] = Set.empty,
  knownObjects: Set[Transform] = Set.empty) extends TransformManager(targets, currentState, knownObjects) {

  // println(transformOrderToGraphviz())

  override val wrappers = Seq(
    (a: Transform) => CatchCustomTransformExceptions(a),
    (a: Transform) => UpdateAnnotations(a)
  )

}
