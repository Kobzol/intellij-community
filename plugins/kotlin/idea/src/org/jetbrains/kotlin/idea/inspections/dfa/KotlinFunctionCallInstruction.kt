// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.idea.inspections.dfa

import com.intellij.codeInspection.dataFlow.CustomMethodHandlers
import com.intellij.codeInspection.dataFlow.DfaCallArguments
import com.intellij.codeInspection.dataFlow.MutationSignature
import com.intellij.codeInspection.dataFlow.TypeConstraints
import com.intellij.codeInspection.dataFlow.interpreter.DataFlowInterpreter
import com.intellij.codeInspection.dataFlow.lang.ir.DfaInstructionState
import com.intellij.codeInspection.dataFlow.lang.ir.ExpressionPushingInstruction
import com.intellij.codeInspection.dataFlow.memory.DfaMemoryState
import com.intellij.codeInspection.dataFlow.types.DfType
import com.intellij.codeInspection.dataFlow.types.DfTypes
import com.intellij.codeInspection.dataFlow.value.DfaControlTransferValue
import com.intellij.codeInspection.dataFlow.value.DfaValue
import com.intellij.codeInspection.dataFlow.value.DfaValueFactory
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.inspections.dfa.KotlinAnchor.KotlinExpressionAnchor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameOrNull
import org.jetbrains.kotlin.resolve.source.PsiSourceElement

// TODO: support Java contracts
// TODO: support Kotlin contracts
class KotlinFunctionCallInstruction(
    private val call: KtExpression,
    private val argCount: Int,
    private val qualifierOnStack: Boolean = false,
    private val exceptionTransfer: DfaControlTransferValue?
) :
    ExpressionPushingInstruction(KotlinExpressionAnchor(call)) {
    override fun accept(interpreter: DataFlowInterpreter, stateBefore: DfaMemoryState): Array<DfaInstructionState> {
        val arguments = popArguments(stateBefore, interpreter)
        val method = getPsiMethod()
        val pure = MutationSignature.fromMethod(method).isPure
        val factory = interpreter.factory
        val resultValue = getMethodReturnValue(factory, stateBefore, method, arguments)
        if (!pure) {
            stateBefore.flushFields()
        }
        val result = mutableListOf<DfaInstructionState>()
        if (exceptionTransfer != null) {
            val exceptional = stateBefore.createCopy()
            result += exceptionTransfer.dispatch(exceptional, interpreter)
        }
        if (resultValue.dfType != DfType.BOTTOM) {
            pushResult(interpreter, stateBefore, resultValue)
            result += nextState(interpreter, stateBefore)
        }
        return result.toTypedArray()
    }

    private fun getMethodReturnValue(
        factory: DfaValueFactory,
        stateBefore: DfaMemoryState,
        method: PsiMethod?,
        arguments: DfaCallArguments
    ): DfaValue {
        if (method != null && arguments.arguments.size == method.parameterList.parametersCount) {
            val handler = CustomMethodHandlers.find(method)
            if (handler != null) {
                val dfaValue = handler.getMethodResultValue(arguments, stateBefore, factory, method)
                if (dfaValue != null) {
                    return dfaValue
                }
            }
        }
        return factory.fromDfType(getExpressionDfType(call))
    }

    private fun popArguments(stateBefore: DfaMemoryState, interpreter: DataFlowInterpreter): DfaCallArguments {
        val args = mutableListOf<DfaValue>()
        // TODO: properly support named and optional arguments
        repeat(argCount) { args += stateBefore.pop() }
        val qualifier: DfaValue = if (qualifierOnStack) stateBefore.pop() else interpreter.factory.unknown
        return DfaCallArguments(qualifier, args.toTypedArray(), MutationSignature.unknown())
    }

    private fun getPsiMethod(): PsiMethod? {
        return when (val source = (call.resolveToCall()?.resultingDescriptor?.source as? PsiSourceElement)?.psi) {
            is KtNamedFunction -> source.toLightMethods().singleOrNull()
            is PsiMethod -> source
            else -> null
        }
    }

    private fun getExpressionDfType(expr: KtExpression): DfType {
        val constructedClassName = (expr.resolveToCall()?.resultingDescriptor as? ConstructorDescriptor)?.constructedClass?.fqNameOrNull()
        if (constructedClassName != null) {
            // Set exact class type for constructor
            val psiClass = JavaPsiFacade.getInstance(expr.project).findClass(constructedClassName.asString(), expr.resolveScope)
            if (psiClass != null) {
                return TypeConstraints.exactClass(psiClass).asDfType().meet(DfTypes.NOT_NULL_OBJECT)
            }
        }
        return expr.getKotlinType().toDfType(expr)
    }

    override fun getSuccessorIndexes(): IntArray {
        return if (exceptionTransfer == null) intArrayOf(index + 1) else exceptionTransfer.possibleTargetIndices + (index + 1)
    }

    override fun toString(): String {
        return "CALL " + call.text
    }
}