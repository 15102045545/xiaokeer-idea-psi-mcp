package com.xiaokeer.idea.psi.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class IdeaPsiProjectActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    ApplicationManager.getApplication()
      .getService(IdeaPsiHttpService::class.java)
      .refreshManifest()
  }
}

