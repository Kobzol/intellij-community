// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.find.impl;

import com.intellij.lang.IdeLanguageCustomization;
import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.ExtensionFileNameMatcher;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class LangFindSettingsImpl extends FindSettingsImpl {

  @Override
  public void loadState(@NotNull FindSettingsImpl state) {
    super.loadState(state);

    Collection<String> extensions = IdeLanguageCustomization.getInstance().getPrimaryIdeLanguages()
      .stream()
      .map(Language::getAssociatedFileType)
      .filter(Objects::nonNull)
      .flatMap(fileType -> Stream.concat(
                 Stream.of(fileType.getDefaultExtension()),
                 getAssociatedExtensions(fileType)
               )
      ).collect(Collectors.toCollection(LinkedHashSet::new));
    
    if (extensions.contains("java")) {
      extensions.add("properties");
      extensions.add("jsp");
    }
    if (!extensions.contains("sql")) {
      extensions.add("xml");
      extensions.add("html");
      extensions.add("css");
    }

    for (String ext : ArrayUtil.reverseArray(ArrayUtil.toStringArray(extensions))) {
      FindInProjectSettingsBase.addRecentStringToList("*." + ext, recentFileMasks);
    }
  }

  private static @NotNull Stream<String> getAssociatedExtensions(@NotNull LanguageFileType fileType) {
    return FileTypeManager.getInstance().getAssociations(fileType)
      .stream()
      .filter(ExtensionFileNameMatcher.class::isInstance)
      .map(ExtensionFileNameMatcher.class::cast)
      .map(ExtensionFileNameMatcher::getExtension);
  }
}
