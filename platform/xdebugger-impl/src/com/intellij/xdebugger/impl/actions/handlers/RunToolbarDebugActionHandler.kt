// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.xdebugger.impl.actions.handlers

import com.intellij.execution.runToolbar.environment
import com.intellij.ide.lightEdit.LightEdit
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.actions.DebuggerActionHandler

abstract class RunToolbarDebugActionHandler() : DebuggerActionHandler() {
  override fun perform(project: Project, event: AnActionEvent) {
      val session = getSession(event)
      if (session is XDebugSessionImpl) {
        perform(session, event.dataContext)
      }
  }

  override fun isEnabled(project: Project, event: AnActionEvent): Boolean {
    if (LightEdit.owns(project)) return false
    return getSession(event)?.let { session ->
      isEnabled(session, event.dataContext)
    } ?: false

  }

  private fun getSession(e: AnActionEvent): XDebugSessionImpl? {
    return e.environment()?.let { environment ->
      e.project?.let {
        XDebuggerManager.getInstance(it)
          ?.debugSessions
          ?.filter { it.runContentDescriptor == environment.contentToReuse }
          ?.filterIsInstance<XDebugSessionImpl>()?.firstOrNull { !it.isStopped }
      }
    }
  }


  protected abstract fun isEnabled(session: XDebugSessionImpl, dataContext: DataContext?): Boolean

  protected abstract fun perform(session: XDebugSessionImpl, dataContext: DataContext?)
}

class RunToolbarResumeActionHandler : RunToolbarDebugActionHandler() {
  override fun isEnabled(session: XDebugSessionImpl, dataContext: DataContext?): Boolean {
    return session.isPaused
  }

  override fun perform(session: XDebugSessionImpl, dataContext: DataContext?) {
    session.resume()
  }
}

class RunToolbarPauseActionHandler : RunToolbarDebugActionHandler() {
  override fun isEnabled(session: XDebugSessionImpl, dataContext: DataContext?): Boolean {
    return session.isPauseActionSupported && !session.isPaused
  }

  override fun perform(session: XDebugSessionImpl, dataContext: DataContext?) {
    session.pause()
  }
}

