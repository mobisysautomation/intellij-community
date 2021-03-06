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
package com.intellij.terminal;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ObservableConsoleView;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.encoding.EncodingProjectManager;
import com.intellij.util.ObjectUtils;
import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TerminalKeyEncoder;
import com.jediterm.terminal.TerminalStarter;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalSession;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.jediterm.terminal.util.CharUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author traff
 */
public class TerminalExecutionConsole implements ConsoleView, ObservableConsoleView {
  private JBTerminalWidget myTerminalWidget;
  private final Project myProject;
  private final AppendableTerminalDataStream myDataStream;
  private final AtomicBoolean myAttachedToProcess = new AtomicBoolean(false);
  private final Collection<ChangeListener> myChangeListeners = new CopyOnWriteArraySet<>();

  private final TerminalKeyEncoder myKeyEncoder = new TerminalKeyEncoder();

  {
    myKeyEncoder.setAutoNewLine(true);
  }

  public TerminalExecutionConsole(@NotNull Project project, @Nullable ProcessHandler processHandler) {
    myProject = project;
    final JBTerminalSystemSettingsProviderBase provider = new JBTerminalSystemSettingsProviderBase() {
      @Override
      public HyperlinkStyle.HighlightMode getHyperlinkHighlightingMode() {
        return HyperlinkStyle.HighlightMode.ALWAYS;
      }
    };

    myDataStream = new AppendableTerminalDataStream();


    myTerminalWidget = new JBTerminalWidget(project, 200, 24, provider, this) {
      private final TerminalInputBuffer myInputBuffer = new TerminalInputBuffer(myTerminal);

      @Override
      protected JBTerminalPanel createTerminalPanel(@NotNull SettingsProvider settingsProvider,
                                                    @NotNull StyleState styleState,
                                                    @NotNull TerminalTextBuffer textBuffer) {
        JBTerminalPanel panel = new JBTerminalPanel((JBTerminalSystemSettingsProviderBase)settingsProvider, textBuffer, styleState) {
          @Override
          public void initKeyHandler() {
            setKeyListener(new TerminalKeyHandler() {
              @Override
              public void keyPressed(KeyEvent e) {
                if (!myInputBuffer.keyPressed(e)) {
                  super.keyPressed(e);
                }
              }
            });
          }
        };

        Disposer.register(this, panel);
        return panel;
      }

      @Override
      protected TerminalStarter createTerminalStarter(JediTerminal terminal, TtyConnector connector) {
        return new TerminalStarter(terminal, connector, myDataStream) {
          @Override
          public byte[] getCode(int key, int modifiers) {
            if (key == 10) {
              return myKeyEncoder.getCode(key, modifiers);
            } else {
              return super.getCode(key, modifiers);
            }
          }

          @Override
          public void sendString(String string) {
            super.sendString(string);
            myInputBuffer.inputStringSent(string); // supports copy-pasted text as well
          }
        };
      }
    };
    Disposer.register(myTerminalWidget, provider);
    if (processHandler != null) {
      attachToProcess(processHandler);
    }
  }

  private void printText(@NotNull String text, @Nullable ConsoleViewContentType contentType) throws IOException {
    if (contentType != null) {
      myDataStream.append(encodeColor(contentType.getAttributes().getForegroundColor()));
    }

    myDataStream.append(text);

    if (contentType != null) {
      myDataStream.append((char)CharUtils.ESC + "[39m"); //restore color
    }
    fireContentAdded(ObjectUtils.notNull(contentType, ConsoleViewContentType.NORMAL_OUTPUT));
  }

  @Override
  public void addChangeListener(@NotNull ChangeListener listener, @NotNull Disposable parent) {
    myChangeListeners.add(listener);
    Disposer.register(parent, () -> myChangeListeners.remove(listener));
  }

  private static String encodeColor(Color color) {
    return String.valueOf((char)CharUtils.ESC) + "[" + "38;2;" + color.getRed() + ";" + color.getGreen() + ";" +
           color.getBlue() + "m";
  }

  public void addMessageFilter(Project project, Filter filter) {
    myTerminalWidget.addMessageFilter(project, filter);
  }

  @Override
  public void print(@NotNull String text, @NotNull ConsoleViewContentType contentType) {
    myTerminalWidget.getTerminal().writeCharacters(text);
    fireContentAdded(contentType);
  }

  private void fireContentAdded(@NotNull ConsoleViewContentType contentType) {
    List<ConsoleViewContentType> contentTypes = Collections.singletonList(contentType);
    for (ChangeListener listener : myChangeListeners) {
      listener.contentAdded(contentTypes);
    }
  }

  @Override
  public void clear() {
    myTerminalWidget.getTerminal().clearScreen();
  }

  @Override
  public void scrollTo(int offset) {
  }

  @Override
  public void attachToProcess(ProcessHandler processHandler) {
    if (processHandler != null) {
      attachToProcess(processHandler, true);
    }
  }

  /**
   * @param processHandler        ProcessHandler instance wrapping underlying PtyProcess
   * @param attachToProcessOutput true if process output should be printed in the console,
   *                              false if output printing is managed externally, e.g. by testing
   *                              console {@code com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView}
   */
  protected final void attachToProcess(@NotNull ProcessHandler processHandler, boolean attachToProcessOutput) {
    if (!myAttachedToProcess.compareAndSet(false, true)) {
      return;
    }
    TerminalSession session = myTerminalWidget
      .createTerminalSession(
        new ProcessHandlerTtyConnector(processHandler, EncodingProjectManager.getInstance(myProject).getDefaultCharset()));

    processHandler.addProcessListener(new ProcessAdapter() {
      @Override
      public void startNotified(@NotNull ProcessEvent event) {
        session.start();
      }

      @Override
      public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        if (attachToProcessOutput) {
          try {
            ConsoleViewContentType contentType = null;
            if (outputType != ProcessOutputTypes.STDOUT) {
              contentType = ConsoleViewContentType.getConsoleViewType(outputType);
            }

            printText(event.getText(), contentType);

            if (outputType == ProcessOutputTypes.SYSTEM) {
              myDataStream.append('\r');
            }
          }
          catch (IOException e) {
            // pass
          }
        }
      }

      @Override
      public void processTerminated(@NotNull ProcessEvent event) {
        ApplicationManager.getApplication().invokeLater(() -> {
          JBTerminalWidget widget = myTerminalWidget;
          if (widget != null) {
            widget.getTerminalPanel().setCursorVisible(false);
          }
        }, ModalityState.any());
      }
    });
  }

  @Override
  public void setOutputPaused(boolean value) {

  }

  @Override
  public boolean isOutputPaused() {
    return false;
  }

  @Override
  public boolean hasDeferredOutput() {
    return false;
  }

  @Override
  public void performWhenNoDeferredOutput(@NotNull Runnable runnable) {
  }

  @Override
  public void setHelpId(@NotNull String helpId) {
  }

  @Override
  public void addMessageFilter(@NotNull Filter filter) {
    addMessageFilter(myProject, filter);
  }

  @Override
  public void printHyperlink(@NotNull String hyperlinkText, @Nullable HyperlinkInfo info) {

  }

  @Override
  public int getContentSize() {
    return 0;
  }

  @Override
  public boolean canPause() {
    return false;
  }

  @NotNull
  @Override
  public AnAction[] createConsoleActions() {
    return AnAction.EMPTY_ARRAY;
  }

  @Override
  public void allowHeavyFilters() {
  }

  @Override
  public JComponent getComponent() {
    return myTerminalWidget.getComponent();
  }

  @Override
  public JComponent getPreferredFocusableComponent() {
    return myTerminalWidget.getComponent();
  }

  @Override
  public void dispose() {
    myTerminalWidget = null;
  }
}
