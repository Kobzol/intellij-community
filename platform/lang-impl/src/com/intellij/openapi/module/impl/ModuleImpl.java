// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.module.impl;

import com.intellij.configurationStore.RenameableStateStorageManager;
import com.intellij.ide.highlighter.ModuleFileType;
import com.intellij.ide.plugins.ContainerDescriptor;
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.components.impl.stores.IComponentStore;
import com.intellij.openapi.components.impl.stores.ModuleStore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleComponent;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.impl.scopes.ModuleScopeProviderImpl;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectEx;
import com.intellij.openapi.roots.ExternalProjectSystemRegistry;
import com.intellij.openapi.roots.ProjectModelElement;
import com.intellij.openapi.roots.ProjectModelExternalSource;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.pointers.VirtualFilePointer;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerListener;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.serviceContainer.ComponentManagerImpl;
import com.intellij.util.xmlb.annotations.MapAnnotation;
import com.intellij.util.xmlb.annotations.Property;
import kotlin.Unit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ModuleImpl extends ComponentManagerImpl implements ModuleEx {
  private static final Logger LOG = Logger.getInstance(ModuleImpl.class);

  @NotNull private final Project myProject;
  @Nullable protected VirtualFilePointer myImlFilePointer;
  private volatile boolean isModuleAdded;

  private String myName;

  private final ModuleScopeProvider myModuleScopeProvider;

  @ApiStatus.Internal
  public ModuleImpl(@NotNull String name, @NotNull Project project, @NotNull String filePath) {
    this(name, project);
    myImlFilePointer = VirtualFilePointerManager.getInstance().create(
      VfsUtilCore.pathToUrl(filePath), this,
      new VirtualFilePointerListener() {
        @Override
        public void validityChanged(@NotNull VirtualFilePointer @NotNull [] pointers) {
          if (myImlFilePointer == null) return;
          VirtualFile virtualFile = myImlFilePointer.getFile();
          if (virtualFile != null) {
            ((ModuleStore)getStore()).setPath(virtualFile.toNioPath(), virtualFile, false);
            ModuleManager.getInstance(myProject).incModificationCount();
          }
        }
      });
  }

  @ApiStatus.Internal
  public ModuleImpl(@NotNull String name, @NotNull Project project, @Nullable VirtualFilePointer virtualFilePointer) {
    this(name, project);

    myImlFilePointer = virtualFilePointer;
  }

  @ApiStatus.Internal
  public ModuleImpl(@NotNull String name, @NotNull Project project) {
    super((ComponentManagerImpl)project);

    registerServiceInstance(Module.class, this, ComponentManagerImpl.fakeCorePluginDescriptor);
    myProject = project;
    myModuleScopeProvider = new ModuleScopeProviderImpl(this);
    myName = name;
  }

  @Override
  public void init(@Nullable Runnable beforeComponentCreation) {
    // do not measure (activityNamePrefix method not overridden by this class)
    // because there are a lot of modules and no need to measure each one
    registerComponents();
    if (!isPersistent()) {
      registerService(IComponentStore.class,
                      NonPersistentModuleStore.class,
                      ComponentManagerImpl.fakeCorePluginDescriptor,
                      true, ServiceDescriptor.PreloadMode.FALSE);
    }
    if (beforeComponentCreation != null) {
      beforeComponentCreation.run();
    }
    createComponents(null);
  }

  private boolean isPersistent() {
    return myImlFilePointer != null;
  }

  @Override
  protected void setProgressDuringInit(@NotNull ProgressIndicator indicator) {
    // Component loading progress is not reported for module, because at this stage minimal reporting unit it is the module itself.
    // Stage "Loading modules" progress reported for each loaded module and module component count doesn't matter.
  }

  @Override
  public final boolean isDisposed() {
    // in case of light project in tests when it's temporarily disposed, the module should be treated as disposed too.
    //noinspection TestOnlyProblems
    return super.isDisposed() || ((ProjectEx)myProject).isLight() && myProject.isDisposed();
  }

  @Override
  protected boolean isComponentSuitable(@NotNull ComponentConfig componentConfig) {
    if (!super.isComponentSuitable(componentConfig)) {
      return false;
    }

    Map<String, String> options = componentConfig.options;
    if (options == null || options.isEmpty()) {
      return true;
    }

    for (String optionName : options.keySet()) {
      if ("workspace".equals(optionName) || "overrides".equals(optionName)) {
        continue;
      }

      // we cannot filter using module options because at this moment module file data could be not loaded
      String message = "Don't specify " + optionName + " in the component registration, transform component to service and implement your logic in your getInstance() method";
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        LOG.error(message);
      }
      else {
        LOG.warn(message);
      }
    }

    return true;
  }

  @Override
  @Nullable
  public VirtualFile getModuleFile() {
    if (myImlFilePointer == null) {
      return null;
    }
    return myImlFilePointer.getFile();
  }

  @Override
  public void rename(@NotNull String newName, boolean notifyStorage) {
    myName = newName;
    if (notifyStorage) {
      ((RenameableStateStorageManager)getStore().getStorageManager()).rename(newName + ModuleFileType.DOT_DEFAULT_EXTENSION);
    }
  }

  protected @NotNull IComponentStore getStore() {
    return Objects.requireNonNull(getService(IComponentStore.class));
  }

  @Override
  @NotNull
  public Path getModuleNioFile() {
    if (!isPersistent()) {
      return Paths.get("");
    }
    return getStore().getStorageManager().expandMacro(StoragePathMacros.MODULE_FILE);
  }

  @Override
  public synchronized void dispose() {
    isModuleAdded = false;
    super.dispose();
  }

  @NotNull
  @Override
  protected ContainerDescriptor getContainerDescriptor(@NotNull IdeaPluginDescriptorImpl pluginDescriptor) {
    return pluginDescriptor.moduleContainerDescriptor;
  }

  @Override
  public void projectOpened() {
    //noinspection deprecation
    processInitializedComponents(ModuleComponent.class, (component, __) -> {
      try {
        //noinspection deprecation
        component.projectOpened();
      }
      catch (Exception e) {
        LOG.error(e);
      }
      return Unit.INSTANCE;
    });
  }

  @Override
  public void projectClosed() {
    //noinspection deprecation
    List<ModuleComponent> components = new ArrayList<>();
    //noinspection deprecation
    processInitializedComponents(ModuleComponent.class, (component, __) -> {
      components.add(component);
      return Unit.INSTANCE;
    });

    for (int i = components.size() - 1; i >= 0; i--) {
      try {
        //noinspection deprecation
        components.get(i).projectClosed();
      }
      catch (Throwable e) {
        LOG.error(e);
      }
    }
  }

  @Override
  @NotNull
  public Project getProject() {
    return myProject;
  }

  @Override
  @NotNull
  public String getName() {
    return myName;
  }

  @Override
  public boolean isLoaded() {
    return isModuleAdded;
  }

  @SuppressWarnings("deprecation")
  @Override
  public void moduleAdded() {
    isModuleAdded = true;
    processInitializedComponents(ModuleComponent.class, (component, __) -> {
      component.moduleAdded();
      return Unit.INSTANCE;
    });
  }

  @Override
  public void setOption(@NotNull String key, @Nullable String value) {
    DeprecatedModuleOptionManager manager = getOptionManager();
    if (value == null) {
      if (manager.state.options.remove(key) != null) {
        manager.incModificationCount();
      }
    }
    else if (!value.equals(manager.state.options.put(key, value))) {
      manager.incModificationCount();
    }
  }

  @NotNull
  private DeprecatedModuleOptionManager getOptionManager() {
    //noinspection ConstantConditions
    return ((Module)this).getService(DeprecatedModuleOptionManager.class);
  }

  @Override
  public String getOptionValue(@NotNull String key) {
    return getOptionManager().state.options.get(key);
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleScope() {
    return myModuleScopeProvider.getModuleScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleScope(boolean includeTests) {
    return myModuleScopeProvider.getModuleScope(includeTests);
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleWithLibrariesScope() {
    return myModuleScopeProvider.getModuleWithLibrariesScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleWithDependenciesScope() {
    return myModuleScopeProvider.getModuleWithDependenciesScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleContentScope() {
    return myModuleScopeProvider.getModuleContentScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleContentWithDependenciesScope() {
    return myModuleScopeProvider.getModuleContentWithDependenciesScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleWithDependenciesAndLibrariesScope(boolean includeTests) {
    return myModuleScopeProvider.getModuleWithDependenciesAndLibrariesScope(includeTests);
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleWithDependentsScope() {
    return myModuleScopeProvider.getModuleWithDependentsScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleTestsWithDependentsScope() {
    return myModuleScopeProvider.getModuleTestsWithDependentsScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleRuntimeScope(boolean includeTests) {
    return myModuleScopeProvider.getModuleRuntimeScope(includeTests);
  }

  @Override
  public void clearScopesCache() {
    myModuleScopeProvider.clearCache();
  }

  @Override
  public String toString() {
    if (myName == null) return "Module (not initialized)";
    return "Module: '" + getName() + "'" + (isDisposed() ? " (disposed)" : "");
  }

  @Override
  public long getOptionsModificationCount() {
    return getOptionManager().getModificationCount();
  }

  @ApiStatus.Internal
  @State(name = "DeprecatedModuleOptionManager", useLoadedStateAsExisting = false /* doesn't make sense to check it */)
  public static class DeprecatedModuleOptionManager extends SimpleModificationTracker implements PersistentStateComponent<DeprecatedModuleOptionManager.State>,
                                                                                          ProjectModelElement {
    private final Module module;

    DeprecatedModuleOptionManager(@NotNull Module module) {
      this.module = module;
    }

    @Override
    @Nullable
    public ProjectModelExternalSource getExternalSource() {
      if (state.options.size() > 1 || state.options.size() == 1 && !state.options.containsKey(Module.ELEMENT_TYPE) /* unrealistic case, but just to be sure */) {
        return null;
      }
      return ExternalProjectSystemRegistry.getInstance().getExternalSource(module);
    }

    static final class State {
      @Property(surroundWithTag = false)
      @MapAnnotation(surroundKeyWithTag = false, surroundValueWithTag = false, surroundWithTag = false, entryTagName = "option")
      public final Map<String, String> options = new HashMap<>();
    }

    private State state = new State();

    @Nullable
    @Override
    public State getState() {
      return state;
    }

    @Override
    public void loadState(@NotNull State state) {
      this.state = state;
    }
  }
}
