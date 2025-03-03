// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.intellij.plugins.markdown.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.impl.AppEditorFontOptions
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.messages.Topic
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanelProvider
import org.intellij.plugins.markdown.ui.preview.jcef.JCEFHtmlPanelProvider

@State(name = "MarkdownSettings", storages = [(Storage("markdown.xml"))])
class MarkdownSettings(val project: Project): SimplePersistentStateComponent<MarkdownSettingsState>(MarkdownSettingsState()) {
  var areInjectionsEnabled
    get() = state.areInjectionsEnabled
    set(value) { state.areInjectionsEnabled = value }

  var hideErrorsInCodeBlocks
    get() = state.hideErrorsInCodeBlocks
    set(value) { state.hideErrorsInCodeBlocks = value }

  var isEnhancedEditingEnabled
    get() = state.isEnhancedEditingEnabled
    set(value) { state.isEnhancedEditingEnabled = value }

  var extensionsEnabledState
    get() = state.enabledExtensions
    set(value) { state.enabledExtensions = value }

  var splitLayout
    get() = state.splitLayout
    set(value) { state.splitLayout = value }

  var previewPanelProviderInfo
    get() = state.previewPanelProviderInfo
    set(value) { state.previewPanelProviderInfo = value }

  var isVerticalSplit
    get() = state.isVerticalSplit
    set(value) { state.isVerticalSplit = value }

  var isAutoScrollEnabled
    get() = state.isAutoScrollEnabled
    set(value) { state.isAutoScrollEnabled = value }

  var useCustomStylesheetPath
    get() = state.useCustomStylesheetPath
    set(value) { state.useCustomStylesheetPath = value }

  var customStylesheetPath
    get() = state.customStylesheetPath
    set(value) { state.customStylesheetPath = value }

  var useCustomStylesheetText
    get() = state.useCustomStylesheetText
    set(value) { state.useCustomStylesheetText = value }

  var customStylesheetText
    get() = state.customStylesheetText
    set(value) { state.customStylesheetText = value }

  var fontSize
    get() = state.fontSize
    set(value) { state.fontSize = value }

  var fontFamily
    get() = state.fontFamily
    set(value) { state.fontFamily = value }

  override fun loadState(state: MarkdownSettingsState) {
    val migrated = possiblyMigrateSettings(state)
    super.loadState(migrated)
  }

  override fun noStateLoaded() {
    super.noStateLoaded()
    loadState(MarkdownSettingsState())
  }

  fun isExtensionEnabled(extensionsId: String): Boolean {
    return state.enabledExtensions[extensionsId] == true
  }

  @Synchronized
  fun update(block: (MarkdownSettings) -> Unit) {
    val publisher = project.messageBus.syncPublisher(ChangeListener.TOPIC)
    publisher.beforeSettingsChanged(this)
    block(this)
    publisher.settingsChanged(this)
  }

  private fun possiblyMigrateSettings(from: MarkdownSettingsState): MarkdownSettingsState {
    @Suppress("DEPRECATION")
    val old = MarkdownApplicationSettings.getInstance().takeIf { it.state != null }
    if (old == null || from.stateVersion == 1) {
      return from
    }
    logger.info("Migrating Markdown settings")
    val migrated = MarkdownSettingsState()
    with(migrated) {
      old.markdownPreviewSettings.let {
        previewPanelProviderInfo = it.htmlPanelProviderInfo
        splitLayout = it.splitEditorLayout
        isAutoScrollEnabled = it.isAutoScrollPreview
        isVerticalSplit = it.isVerticalSplit
      }
      old.markdownCssSettings.let {
        customStylesheetPath = it.customStylesheetPath.takeIf { _ -> it.isCustomStylesheetEnabled }
        customStylesheetText = it.customStylesheetText.takeIf { _ -> it.isTextEnabled }
        fontFamily = it.fontFamily
        fontSize = it.fontSize
      }
      enabledExtensions = old.extensionsEnabledState
      areInjectionsEnabled = !old.isDisableInjections
      hideErrorsInCodeBlocks = old.isHideErrors
      isEnhancedEditingEnabled = old.isEnhancedEditingEnabled
      stateVersion = 1
      resetModificationCount()
    }
    return migrated
  }

  interface ChangeListener {
    fun beforeSettingsChanged(settings: MarkdownSettings) = Unit

    fun settingsChanged(settings: MarkdownSettings) = Unit

    companion object {
      @JvmField
      val TOPIC = Topic.create("MarkdownSettingsChanged", ChangeListener::class.java)
    }
  }

  //@State(name = "MarkdownSettingsMigration", storages = [(Storage("markdown.xml"))])
  //internal class SettingsMigration: SimplePersistentStateComponent<SettingsMigration.State>(State()) {
  //  class State: BaseState() {
  //    var version by property(0)
  //  }
  //
  //  companion object {
  //    fun getInstance(project: Project): SettingsMigration = project.service()
  //  }
  //}

  companion object {
    private val logger = logger<MarkdownSettings>()

    @JvmStatic
    val defaultFontSize
      get() = JBCefApp.normalizeScaledSize(checkNotNull(AppEditorFontOptions.getInstance().state).FONT_SIZE)

    @JvmStatic
    val defaultFontFamily
      get() = checkNotNull(AppEditorFontOptions.getInstance().state).FONT_FAMILY

    @JvmStatic
    val defaultProviderInfo
      get() = when {
        JBCefApp.isSupported() -> JCEFHtmlPanelProvider().providerInfo
        else -> MarkdownHtmlPanelProvider.ProviderInfo("Unavailable", "Unavailable")
      }

    @JvmStatic
    fun getInstance(project: Project): MarkdownSettings {
      return project.service()
    }

    @JvmStatic
    fun getInstanceForDefaultProject(): MarkdownSettings {
      return ProjectManager.getInstance().defaultProject.service()
    }
  }
}
