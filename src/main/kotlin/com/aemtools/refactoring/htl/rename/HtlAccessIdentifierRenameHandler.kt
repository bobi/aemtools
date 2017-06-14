package com.aemtools.refactoring.htl.rename

import com.aemtools.completion.util.virtualFile
import com.aemtools.lang.htl.psi.mixin.AccessIdentifierMixin
import com.aemtools.util.psiManager
import com.aemtools.util.showErrorMessage
import com.intellij.featureStatistics.FeatureUsageTracker
import com.intellij.ide.scratch.ScratchFileType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil
import com.intellij.psi.meta.PsiMetaOwner
import com.intellij.psi.meta.PsiWritableMetaData
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.actions.BaseRefactoringAction
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.PsiElementRenameHandler.DEFAULT_NAME
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.UsageViewUtil
import java.util.*

/**
 * @author Dmytro Troynikov
 */
class HtlAccessIdentifierRenameHandler : RenameHandler {

    override fun isRenaming(dataContext: DataContext?): Boolean {
        if (dataContext == null) {
            return false
        }

        val accessIdentifier = PsiElementRenameHandler.getElement(dataContext) as? AccessIdentifierMixin
                ?: return false

        return true
    }

    override fun isAvailableOnDataContext(dataContext: DataContext?): Boolean {
        return isRenaming(dataContext)
    }

    override fun invoke(project: Project,
                        editor: Editor?,
                        file: PsiFile?,
                        dataContext: DataContext?) {
        if (editor == null || file == null || dataContext == null) {
            return
        }

        val element = PsiElementRenameHandler.getElement(dataContext)
                ?: BaseRefactoringAction.getElementAtCaret(editor, file)
                ?: return

        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)

        val nameSuggestionContext = InjectedLanguageUtil.findElementAtNoCommit(file, editor.caretModel.offset)

        RenameUtil.invoke(element, project, nameSuggestionContext, editor)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        if (dataContext == null) {
            return
        }

        val element = elements.firstOrNull()
                ?: RenameUtil.getElement(dataContext)
                ?: return
        val editor = CommonDataKeys.EDITOR.getData(dataContext)

        if (ApplicationManager.getApplication().isUnitTestMode) {
            val newName = DEFAULT_NAME.getData(dataContext)
            rename(element, project, element, editor, newName)
        } else {
            RenameUtil.invoke(element, project, element, editor)
        }

    }

    companion object RenameUtil {
        fun invoke(element: PsiElement,
                   project: Project,
                   nameSuggestionContext: PsiElement,
                   editor: Editor?) {
            if (!canRename(project, editor, element)) {
                return
            }

            val contextFile = nameSuggestionContext.virtualFile()

            if (nameSuggestionContext.isPhysical
                    && (contextFile == null || contextFile.fileType != ScratchFileType.INSTANCE)
                    && !project.psiManager().isInProject(nameSuggestionContext)) {
                val message = "Selected element is used from non-project files. These usages won't be renamed. Proceed anyway?"
                if (ApplicationManager.getApplication().isUnitTestMode) {
                    throw CommonRefactoringUtil.RefactoringErrorHintException(message)
                }

                if (Messages.showYesNoDialog(
                        project,
                        message,
                        RefactoringBundle.getCannotRefactorMessage(null),
                        Messages.getWarningIcon()) != Messages.YES) {
                    return
                }
            }

            FeatureUsageTracker.getInstance().triggerFeatureUsed("refactoring.rename")

            rename(element, project, nameSuggestionContext, editor)
        }

        fun rename(element: PsiElement,
                   project: Project,
                   nameSuggestionContext: PsiElement,
                   editor: Editor?,
                   defaultName: String? = null
        ): Unit {
            val processor = RenamePsiElementProcessor.forElement(element)
            val substituted = processor.substituteElementToRename(element, editor)

            if (substituted == null || !canRename(project, editor, substituted)) {
                return
            }

            val dialog = processor.createRenameDialog(project, substituted, nameSuggestionContext, editor)


            val _defaultName = if (defaultName == null && ApplicationManager.getApplication().isUnitTestMode) {
                val strings = dialog.suggestedNames
                if (strings != null && strings.isNotEmpty()) {
                    Arrays.sort(strings)
                    strings[0]
                } else {
                    "undefined"
                }
            } else {
                defaultName
            }

            if (_defaultName != null) {
                try {
                    dialog.performRename(_defaultName)
                } finally {
                    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
            } else {
                dialog.show()
            }
        }

        fun canRename(project: Project,
                      editor: Editor?,
                      element: PsiElement): Boolean {
            val message = renameabilityStatus(project, element)
            if (message != null && StringUtil.isNotEmpty(message)) {
                showErrorMessage(project, editor, message)
                return false
            }
            return true
        }

        fun renameabilityStatus(project: Project, element: PsiElement?): String? {
            if (element == null) {
                return ""
            }

            val hasRenameProcessor = RenamePsiElementProcessor.forElement(element) != RenamePsiElementProcessor.DEFAULT
            val hasWritableMetaData = element is PsiMetaOwner
                    && element.metaData is PsiWritableMetaData

            if (!hasRenameProcessor && !hasWritableMetaData && element !is PsiNamedElement) {
                return RefactoringBundle.getCannotRefactorMessage(RefactoringBundle.message("error.wrong.caret.position.symbol.to.rename"))
            }

            if (!project.psiManager().isInProject(element)) {
                if (element.isPhysical) {
                    val virtualFile = element.virtualFile()

                    if (!(virtualFile != null
                            && NonProjectFileWritingAccessProvider.isWriteAccessAllowed(virtualFile, project))) {
                        val message = RefactoringBundle.message("error.out.of.project.element",
                                UsageViewUtil.getType(element))
                        return RefactoringBundle.getCannotRefactorMessage(message)
                    }
                }

                if (!element.isWritable) {
                    return RefactoringBundle.getCannotRefactorMessage(
                            RefactoringBundle.message("error.cannot.be.renamed")
                    )
                }
            }

            if (InjectedLanguageUtil.isInInjectedLanguagePrefixSuffix(element)) {
                val message = RefactoringBundle.message(
                        "error.in.injected.lang.prefix.suffix",
                        UsageViewUtil.getType(element)
                )
                return RefactoringBundle.getCannotRefactorMessage(message)
            }

            return null
        }

        fun getElement(dataContext: DataContext?): PsiElement? {
            val elements = BaseRefactoringAction.getPsiElementArray(dataContext)
            return elements.firstOrNull()
        }
    }

}
