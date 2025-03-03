// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.intentions

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.IntentionWrapper
import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.codeInspection.util.IntentionName
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.inspections.IntentionBasedInspection
import org.jetbrains.kotlin.idea.util.application.isUnitTestMode
import org.jetbrains.kotlin.idea.util.application.withPsiAttachment
import org.jetbrains.kotlin.psi.CREATE_BY_PATTERN_MAY_NOT_REFORMAT
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.containsInside
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.utils.checkWithAttachment

@Suppress("EqualsOrHashCode")
abstract class SelfTargetingIntention<TElement : PsiElement>(
    val elementType: Class<TElement>,
    private var textGetter: () -> @IntentionName String,
    private val familyNameGetter: () -> @IntentionFamilyName String = textGetter,
) : IntentionAction {
    @Deprecated("Replace with primary constructor", ReplaceWith("SelfTargetingIntention<TElement>(elementType, { text }, { familyName })"))
    constructor(
        elementType: Class<TElement>,
        text: @IntentionName String,
        familyName: @IntentionFamilyName String = text,
    ) : this(elementType, { text }, { familyName })

    protected val defaultText: @IntentionName String get() = defaultTextGetter()
    protected val defaultTextGetter: () -> @IntentionName String = textGetter

    @Deprecated("Replace with `setTextGetter`", ReplaceWith("setTextGetter { text }"))
    protected fun setText(@IntentionName text: String) {
        this.textGetter = { text }
    }

    protected fun setTextGetter(textGetter: () -> @IntentionName String) {
        this.textGetter = textGetter
    }

    final override fun getText(): @IntentionName String = textGetter()
    final override fun getFamilyName(): @IntentionFamilyName String = familyNameGetter()

    abstract fun isApplicableTo(element: TElement, caretOffset: Int): Boolean

    abstract fun applyTo(element: TElement, editor: Editor?)

    protected open val isKotlinOnlyIntention: Boolean = true

    fun getTarget(offset: Int, file: PsiFile): TElement? {
        val leaf1 = file.findElementAt(offset)
        val leaf2 = file.findElementAt(offset - 1)
        val commonParent = if (leaf1 != null && leaf2 != null) PsiTreeUtil.findCommonParent(leaf1, leaf2) else null

        var elementsToCheck: Sequence<PsiElement> = emptySequence()
        if (leaf1 != null) elementsToCheck += leaf1.parentsWithSelf.takeWhile { it != commonParent }
        if (leaf2 != null) elementsToCheck += leaf2.parentsWithSelf.takeWhile { it != commonParent }
        if (commonParent != null && commonParent !is PsiFile) elementsToCheck += commonParent.parentsWithSelf

        for (element in elementsToCheck) {
            @Suppress("UNCHECKED_CAST")
            if (elementType.isInstance(element)) {
                ProgressManager.checkCanceled()
                if (isApplicableTo(element as TElement, offset)) {
                    return element
                }
            }

            if (!allowCaretInsideElement(element)) {
                val elementTextRange = element.textRange
                checkWithAttachment(elementTextRange != null, {
                    "No text range defined for the ${if (element.isValid) "valid" else "invalid"} element $element"
                }) {
                    it.withAttachment("intention.txt", this::class)
                    it.withPsiAttachment("element.kt", element)
                    it.withPsiAttachment("file.kt", element.containingFile)
                }

                if (elementTextRange.containsInside(offset)) break
            }
        }
        return null
    }

    fun getTarget(editor: Editor, file: PsiFile): TElement? {
        if (isKotlinOnlyIntention && file !is KtFile) return null

        val offset = editor.caretModel.offset
        return getTarget(offset, file)
    }

    protected open fun allowCaretInsideElement(element: PsiElement): Boolean = element !is KtBlockExpression

    final override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (isUnitTestMode()) {
            CREATE_BY_PATTERN_MAY_NOT_REFORMAT = true
        }
        try {
            return getTarget(editor, file) != null
        } finally {
            CREATE_BY_PATTERN_MAY_NOT_REFORMAT = false
        }
    }

    var inspection: IntentionBasedInspection<TElement>? = null
        internal set

    final override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
        editor ?: return
        val target = getTarget(editor, file) ?: return
        if (!preparePsiElementForWriteIfNeeded(target)) return
        applyTo(target, editor)
    }

    /**
     * If [startInWriteAction] returns true, that means that the platform already called `preparePsiElementForWrite`
     * for us (we do not want to call it again because it will throw if the intention is used with Intention Preview).
     *
     * Otherwise, we have to call it ourselves (see javadoc for [getElementToMakeWritable]).
     */
    private fun preparePsiElementForWriteIfNeeded(target: TElement): Boolean {
        if (startInWriteAction()) return true
        return FileModificationService.getInstance().preparePsiElementForWrite(target)
    }

    override fun startInWriteAction() = true

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean {
        // Nasty code because IntentionWrapper itself does not override equals
        if (other is IntentionWrapper) return this == other.action
        if (other is IntentionBasedInspection<*>.IntentionBasedQuickFix) return this == other.intention
        return other is SelfTargetingIntention<*> && javaClass == other.javaClass && text == other.text
    }

    // Intentionally missed hashCode (IntentionWrapper does not override it)
}

abstract class SelfTargetingRangeIntention<TElement : PsiElement>(
    elementType: Class<TElement>,
    textGetter: () -> @IntentionName String,
    familyNameGetter: () -> @IntentionFamilyName String = textGetter,
) : SelfTargetingIntention<TElement>(elementType, textGetter, familyNameGetter) {

    @Deprecated(
        "Replace with primary constructor",
        ReplaceWith("SelfTargetingRangeIntention<TElement>(elementType, { text }, { familyName })")
    )
    constructor(
        elementType: Class<TElement>,
        text: @IntentionName String,
        familyName: @IntentionFamilyName String = text,
    ) : this(elementType, { text }, { familyName })

    abstract fun applicabilityRange(element: TElement): TextRange?

    final override fun isApplicableTo(element: TElement, caretOffset: Int): Boolean {
        val range = applicabilityRange(element) ?: return false
        return range.containsOffset(caretOffset)
    }
}

abstract class SelfTargetingOffsetIndependentIntention<TElement : KtElement>(
    elementType: Class<TElement>,
    textGetter: () -> @IntentionName String,
    familyNameGetter: () -> @IntentionFamilyName String = textGetter,
) : SelfTargetingRangeIntention<TElement>(elementType, textGetter, familyNameGetter) {

    @Deprecated(
        "Replace with primary constructor",
        ReplaceWith("SelfTargetingOffsetIndependentIntention<TElement>(elementType, { text }, { familyName })")
    )
    constructor(
        elementType: Class<TElement>,
        text: @IntentionName String,
        familyName: @IntentionFamilyName String = text,
    ) : this(elementType, { text }, { familyName })

    abstract fun isApplicableTo(element: TElement): Boolean

    final override fun applicabilityRange(element: TElement): TextRange? {
        return if (isApplicableTo(element)) element.textRange else null
    }
}
