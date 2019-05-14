// See LICENSE for license details.

package firrtl.stage

import firrtl._
import firrtl.Transform

/*
 * - InferWidths should have InferTypes split out
 * - ConvertFixedToSInt should have InferTypes split out
 * - Move InferTypes out of ZeroWidth
 */

object Forms {

  private implicit def classHelper(a: Class[_ <: Transform]): Class[Transform] = a.asInstanceOf[Class[Transform]]

  val ChirrtlForm: Set[Class[Transform]] = Set.empty

  val HighForm: Set[Class[Transform]] = ChirrtlForm ++
    Set[Class[Transform]]( classOf[passes.CheckChirrtl],
                           classOf[passes.CInferTypes],
                           classOf[passes.CInferMDir],
                           classOf[passes.RemoveCHIRRTL] )

  val WorkingIR: Set[Class[Transform]] = HighForm + classOf[passes.ToWorkingIR]

  val Resolved: Set[Class[Transform]] = WorkingIR ++
    Set[Class[Transform]]( classOf[passes.CheckHighForm],
                           classOf[passes.ResolveKinds],
                           classOf[passes.InferTypes],
                           classOf[passes.CheckTypes],
                           classOf[passes.Uniquify],
                           classOf[passes.ResolveGenders],
                           classOf[passes.CheckGenders],
                           classOf[passes.InferWidths],
                           classOf[passes.CheckWidths] )

  val Deduped: Set[Class[Transform]] = Resolved + classOf[firrtl.transforms.DedupModules]

  val MidForm: Set[Class[Transform]] = Deduped ++
    Set[Class[Transform]]( classOf[passes.PullMuxes],
                           classOf[passes.ReplaceAccesses],
                           classOf[passes.ExpandConnects],
                           classOf[passes.RemoveAccesses],
                           classOf[passes.ExpandWhensAndCheck],
                           classOf[passes.ConvertFixedToSInt],
                           classOf[passes.ZeroWidth] )

  val LowForm: Set[Class[Transform]] = MidForm ++
    Set[Class[Transform]]( classOf[passes.LowerTypes],
                           classOf[passes.Legalize],
                           classOf[firrtl.transforms.RemoveReset],
                           classOf[firrtl.transforms.CheckCombLoops],
                           classOf[firrtl.transforms.RemoveWires] )

  val LowFormMinimumOptimized: Set[Class[Transform]] = LowForm ++
    Set[Class[Transform]]( classOf[passes.RemoveValidIf],
                           classOf[passes.memlib.VerilogMemDelays],
                           classOf[passes.SplitExpressions] )

  val LowFormOptimized: Set[Class[Transform]] = LowFormMinimumOptimized ++
    Set[Class[Transform]]( classOf[firrtl.transforms.ConstantPropagation],
                           classOf[passes.PadWidths],
                           classOf[firrtl.transforms.CombineCats],
                           classOf[passes.CommonSubexpressionElimination],
                           classOf[firrtl.transforms.DeadCodeElimination] )

  val VerilogMinimumOptimized: Set[Class[Transform]] = LowFormMinimumOptimized ++
    Set[Class[Transform]]( classOf[firrtl.transforms.BlackBoxSourceHelper],
                           classOf[firrtl.transforms.ReplaceTruncatingArithmetic],
                           classOf[firrtl.transforms.FlattenRegUpdate],
                           classOf[passes.VerilogModulusCleanup],
                           classOf[firrtl.transforms.VerilogRename],
                           classOf[passes.VerilogPrep],
                           classOf[firrtl.AddDescriptionNodes] )

  val VerilogOptimized: Set[Class[Transform]] = LowFormOptimized ++
    Set[Class[Transform]]( classOf[firrtl.transforms.BlackBoxSourceHelper],
                           classOf[firrtl.transforms.ReplaceTruncatingArithmetic],
                           classOf[firrtl.transforms.FlattenRegUpdate],
                           classOf[passes.VerilogModulusCleanup],
                           classOf[firrtl.transforms.VerilogRename],
                           classOf[passes.VerilogPrep],
                           classOf[firrtl.AddDescriptionNodes] )

}
