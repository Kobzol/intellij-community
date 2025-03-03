/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.util.gist;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.NullableFunction;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import com.intellij.util.indexing.FileContentImpl;
import com.intellij.util.io.DataExternalizer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * @author peter
 */
class PsiFileGistImpl<Data> implements PsiFileGist<Data> {
  private static final ModificationTracker ourReindexTracker = () -> ((GistManagerImpl)GistManager.getInstance()).getReindexCount();
  private final VirtualFileGist<Data> myPersistence;
  private final VirtualFileGist.GistCalculator<Data> myCalculator;
  private final Key<CachedValue<Data>> myCacheKey;

  PsiFileGistImpl(@NotNull String id,
                  int version,
                  @NotNull DataExternalizer<Data> externalizer,
                  @NotNull NullableFunction<? super PsiFile, ? extends Data> calculator) {
    myCalculator = (project, file) -> {
      PsiFile psiFile = getPsiFile(project, file);
      return psiFile == null ? null : calculator.fun(psiFile);
    };
    myPersistence = GistManager.getInstance().newVirtualFileGist(id, version, externalizer, myCalculator);
    myCacheKey = Key.create("PsiFileGist " + id);
  }

  @Override
  public Data getFileData(@NotNull PsiFile file) {
    ApplicationManager.getApplication().assertReadAccessAllowed();

    if (GistManager.getInstance().shouldUseMemoryStorage(file)) {
      return CachedValuesManager.getManager(file.getProject()).getCachedValue(
        file, myCacheKey, () -> {
          Data data = myCalculator.calcData(getProjectForPersistence(file), getVirtualFile(file));
          return CachedValueProvider.Result.create(data, file, ourReindexTracker);
        }, false);
    }

    file.putUserData(myCacheKey, null);
    return myPersistence.getFileData(getProjectForPersistence(file), getVirtualFile(file));
  }

  @Override
  @RequiresReadLock
  public @Nullable Supplier<Data> getUpToDateOrNull(@NotNull PsiFile file) {
    if (GistManager.getInstance().shouldUseMemoryStorage(file)) {
      CachedValue<Data> data = file.getUserData(myCacheKey);
      return data != null ? data.getUpToDateOrNull() : null;
    }

    return ((VirtualFileGistImpl<Data>)myPersistence).getUpToDateOrNull(getProjectForPersistence(file), getVirtualFile(file));
  }

  static @NotNull VirtualFile getVirtualFile(@NotNull PsiFile file) {
    return file.getViewProvider().getVirtualFile();
  }

  protected @Nullable Project getProjectForPersistence(@NotNull PsiFile file) {
    return file.getProject();
  }

  protected @Nullable PsiFile getPsiFile(@NotNull Project project, @NotNull VirtualFile file) {
    PsiFile psi = PsiManager.getInstance(project).findFile(file);
    if (!(psi instanceof PsiFileImpl) || ((PsiFileImpl)psi).isContentsLoaded()) {
      return psi;
    }

    FileType fileType = file.getFileType();
    if (!(fileType instanceof LanguageFileType)) return null;

    return FileContentImpl.createFileFromText(project, psi.getViewProvider().getContents(), (LanguageFileType)fileType, file, file.getName());
  }

}
