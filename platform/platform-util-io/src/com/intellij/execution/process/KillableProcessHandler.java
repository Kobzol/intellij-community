// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.process;

import com.google.common.base.Ascii;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.KillableProcess;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Set;

/**
 * This process handler supports the "soft-kill" feature (see {@link KillableProcessHandler}).
 * At first "stop" button send SIGINT signal to process, if it still hangs user can terminate it recursively with SIGKILL signal.
 * <p>
 * Soft kill works on Unix, and also on Windows if a mediator process was used.
 */
public class KillableProcessHandler extends OSProcessHandler implements KillableProcess {
  private static final Logger LOG = Logger.getInstance(KillableProcessHandler.class);

  private boolean myShouldKillProcessSoftly = true;
  private final boolean myMediatedProcess;
  private boolean myShouldKillProcessSoftlyWithWinP = SystemInfo.isWin10OrNewer && Registry.is("use.winp.for.graceful.process.termination");

  public KillableProcessHandler(@NotNull GeneralCommandLine commandLine) throws ExecutionException {
    super(commandLine);
    myMediatedProcess = RunnerMediator.isRunnerCommandInjected(commandLine);
  }

  protected KillableProcessHandler(@NotNull Process process, @NotNull GeneralCommandLine commandLine) {
    super(process, commandLine.getCommandLineString(), commandLine.getCharset());
    myMediatedProcess = RunnerMediator.isRunnerCommandInjected(commandLine);
  }

  /**
   * Starts a process with a {@link RunnerMediator mediator} when {@code withMediator} is set to {@code true} and the platform is Windows.
   */
  public KillableProcessHandler(@NotNull GeneralCommandLine commandLine, boolean withMediator) throws ExecutionException {
    this(mediate(commandLine, withMediator, false));
  }

  /**
   * {@code commandLine} must not be empty (for correct thread attribution in the stacktrace)
   */
  public KillableProcessHandler(@NotNull Process process, /*@NotNull*/ String commandLine) {
    super(process, commandLine);
    myMediatedProcess = false;
  }

  /**
   * {@code commandLine} must not be empty (for correct thread attribution in the stacktrace)
   */
  public KillableProcessHandler(@NotNull Process process, /*@NotNull*/ String commandLine, @NotNull Charset charset) {
    this(process, commandLine, charset, null);
  }

  /**
   * {@code commandLine} must not be empty (for correct thread attribution in the stacktrace)
   */
  public KillableProcessHandler(@NotNull Process process, /*@NotNull*/ String commandLine, @NotNull Charset charset, @Nullable Set<File> filesToDelete) {
    super(process, commandLine, charset, filesToDelete);
    myMediatedProcess = false;
  }

  @NotNull
  protected static GeneralCommandLine mediate(@NotNull GeneralCommandLine commandLine, boolean withMediator, boolean showConsole) {
    if (withMediator && SystemInfo.isWindows) {
      RunnerMediator.injectRunnerCommand(commandLine, showConsole);
    }
    return commandLine;
  }

  /**
   * @return {@code true} if graceful process termination should be attempted first
   */
  public boolean shouldKillProcessSoftly() {
    return myShouldKillProcessSoftly;
  }

  /**
   * Sets whether the process will be terminated gracefully.
   *
   * @param shouldKillProcessSoftly {@code true} if graceful process termination should be attempted first (i.e. "soft kill")
   */
  public void setShouldKillProcessSoftly(boolean shouldKillProcessSoftly) {
    myShouldKillProcessSoftly = shouldKillProcessSoftly;
  }

  /**
   * This method shouldn't be overridden, see {@link #shouldKillProcessSoftly}
   * @see #destroyProcessGracefully
   */
  private boolean canDestroyProcessGracefully() {
    if (processCanBeKilledByOS(myProcess)) {
      if (SystemInfo.isWindows) {
        return hasPty() || myMediatedProcess || canTerminateGracefullyWithWinP();
      }
      if (SystemInfo.isUnix) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected void destroyProcessImpl() {
    // Don't close streams, because a process may survive graceful termination.
    // Streams will be closed after the process is really terminated.
    try {
      myProcess.getOutputStream().flush();
    }
    catch (IOException e) {
      LOG.warn(e);
    }
    finally {
      doDestroyProcess();
    }
  }

  @Override
  protected void notifyProcessTerminated(int exitCode) {
    try {
      super.closeStreams();
    }
    finally {
      super.notifyProcessTerminated(exitCode);
    }
  }

  @Override
  protected void doDestroyProcess() {
    boolean gracefulTerminationAttempted = shouldKillProcessSoftly() && canDestroyProcessGracefully() && destroyProcessGracefully();
    if (!gracefulTerminationAttempted) {
      // execute default process destroy
      super.doDestroyProcess();
    }
  }

  /**
   * Enables sending Ctrl+C to a Windows-process on first termination attempt.
   * This is an experimental API which will be removed in future releases once stabilized.
   * Please do not use this API.
   * @param shouldKillProcessSoftlyWithWinP true to use
   * @deprecated graceful termination with WinP is enabled by default; please don't use this method
   */
  @ApiStatus.Experimental
  @Deprecated
  @ApiStatus.ScheduledForRemoval(inVersion = "2022.1")
  public void setShouldKillProcessSoftlyWithWinP(boolean shouldKillProcessSoftlyWithWinP) {
    myShouldKillProcessSoftlyWithWinP = shouldKillProcessSoftlyWithWinP;
  }

  private boolean canTerminateGracefullyWithWinP() {
    return myShouldKillProcessSoftlyWithWinP && SystemInfo.isWin10OrNewer && !isWslProcess();
  }

  /**
   * Checks if the process is WSL.
   * WinP's graceful termination doesn't work for Linux processes started inside WSL, like
   * "wsl.exe -d Ubuntu-20.04 --exec <linux command>", because WinP's `org.jvnet.winp.WinProcess.sendCtrlC`
   * uses `GenerateConsoleCtrlEvent` under the hood and `GenerateConsoleCtrlEvent` doesn't terminate Linux
   * processes running in WSL. Instead, it terminates wsl.exe process only.
   * See <a href="https://github.com/microsoft/WSL/issues/7301">WSL issue #7301</a> for the details.
   */
  private boolean isWslProcess() {
    ProcessHandle.Info info = null;
    try {
      info = myProcess.info();
    }
    catch (UnsupportedOperationException ignored) {
    }
    String command = info != null ? info.command().orElse(null) : null;
    boolean wsl = command != null && PathUtil.getFileName(command).equals("wsl.exe");
    if (wsl) {
      LOG.info("Skipping WinP graceful termination for " + command + " due to incorrect work with WSL processes");
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("[graceful termination with WinP] WSL process: " + wsl  + ", executable: " + command + ", info: " + info);
    }
    return wsl;
  }

  protected boolean destroyProcessGracefully() {
    if (hasPty() && sendInterruptToPtyProcess()) {
      return true;
    }
    if (SystemInfo.isWindows) {
      if (myMediatedProcess) {
        return RunnerMediator.destroyProcess(myProcess, true);
      }
      if (canTerminateGracefullyWithWinP() && !Registry.is("disable.winp")) {
        try {
          if (!myProcess.isAlive()) {
            OSProcessUtil.logSkippedActionWithTerminatedProcess(myProcess, "destroy", getCommandLine());
            return true;
          }
          return ProcessService.getInstance().sendWinProcessCtrlC(myProcess);
        }
        catch (Throwable e) {
          if (!myProcess.isAlive()) {
            OSProcessUtil.logSkippedActionWithTerminatedProcess(myProcess, "destroy", getCommandLine());
            return true;
          }
          String message = e.getMessage();
          if (message != null && message.contains(".exe terminated with exit code 6,")) {
            // https://github.com/kohsuke/winp/blob/ec4ac6a988f6e3909c57db0abc4b02ff1b1d2e05/native/sendctrlc/main.cpp#L18
            // WinP uses AttachConsole(pid) which might fail if the specified process does not have a console.
            // In this case, the error code returned is ERROR_INVALID_HANDLE (6).
            // Let's fall back to the default termination without logging an error.
            String msg = "Cannot send Ctrl+C to process without a console (fallback to default termination)";
            if (LOG.isDebugEnabled()) {
              LOG.debug(msg + " " + getCommandLine());
            }
            else {
              LOG.info(msg);
            }
          }
          else {
            LOG.error("Cannot send Ctrl+C (fallback to default termination) " + getCommandLine(), e);
          }
        }
      }
    }
    else if (SystemInfo.isUnix) {
      return UnixProcessManager.sendSigIntToProcessTree(myProcess);
    }
    return false;
  }

  /**
   * Writes the INTR (interrupt) character to process's stdin (PTY). When a PTY receives the INTR character,
   * it raises a SIGINT signal for all processes in the foreground job associated with the PTY. The character itself is then discarded.
   * <p>A proper way to get INTR is `termios.c_cc[VINTR]`. However, unlikely, the default (003, ETX) is changed.
   * <p>Works on Unix and Windows.
   * 
   * @see <a href="https://man7.org/linux/man-pages/man3/tcflow.3.html">termios(3)</a>
   * @see <a href="https://www.gnu.org/software/libc/manual/html_node/Signal-Characters.html">Characters that Cause Signals</a>
   * 
   * @return true if the character has been written successfully
   */
  private boolean sendInterruptToPtyProcess() {
    OutputStream outputStream = myProcess.getOutputStream();
    if (outputStream != null) {
      try {
        outputStream.write(Ascii.ETX);
        outputStream.flush();
        return true;
      }
      catch (IOException e) {
        LOG.info("Failed to send Ctrl+C to PTY process. Fallback to default graceful termination.", e);
      }
    }
    return false;
  }

  @Override
  public boolean canKillProcess() {
    return processCanBeKilledByOS(getProcess()) || getProcess() instanceof ProcessTreeKiller;
  }

  @Override
  public void killProcess() {
    if (processCanBeKilledByOS(getProcess())) {
      // execute 'kill -SIGKILL <pid>' on Unix
      killProcessTree(getProcess());
    }
    else if (getProcess() instanceof ProcessTreeKiller) {
      ((ProcessTreeKiller)getProcess()).killProcessTree();
    }
  }
}
