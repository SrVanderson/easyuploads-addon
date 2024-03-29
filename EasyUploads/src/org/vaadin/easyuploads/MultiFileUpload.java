package org.vaadin.easyuploads;

import java.io.File;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vaadin.easyuploads.MultiUpload.FileDetail;
import org.vaadin.easyuploads.UploadField.FieldType;

import com.vaadin.event.dd.DragAndDropEvent;
import com.vaadin.event.dd.DropHandler;
import com.vaadin.event.dd.acceptcriteria.AcceptAll;
import com.vaadin.event.dd.acceptcriteria.AcceptCriterion;
import com.vaadin.terminal.StreamVariable;
import com.vaadin.terminal.StreamVariable.StreamingEndEvent;
import com.vaadin.terminal.StreamVariable.StreamingErrorEvent;
import com.vaadin.terminal.StreamVariable.StreamingProgressEvent;
import com.vaadin.terminal.StreamVariable.StreamingStartEvent;
import com.vaadin.terminal.gwt.server.AbstractWebApplicationContext;
import com.vaadin.terminal.gwt.server.WebBrowser;
import com.vaadin.ui.Component;
import com.vaadin.ui.CssLayout;
import com.vaadin.ui.DragAndDropWrapper;
import com.vaadin.ui.DragAndDropWrapper.WrapperTransferable;
import com.vaadin.ui.Html5File;
import com.vaadin.ui.Label;
import com.vaadin.ui.ProgressIndicator;

/**
 * MultiFileUpload makes it easier to upload multiple files. MultiFileUpload
 * releases upload button for new uploads immediately when a file is selected
 * (aka parallel uploads). It also displays progress indicators for pending
 * uploads.
 * <p>
 * MultiFileUpload always streams straight to files to keep memory consumption
 * low. To temporary files by default, but this can be overridden with
 * {@link #setFileFactory(FileFactory)} (eg. straight to target directory on the
 * server).
 * <p>
 * Developer handles uploaded files by implementing the abstract
 * {@link #handleFile(File, String, String, long)} method.
 * <p>
 * TODO Field version (type == Collection<File> or File where isDirectory() ==
 * true).
 * <p>
 * TODO a super progress indicator (total transferred per total, including
 * queued files)
 * <p>
 * TODO Time remaining estimates and current transfer rate
 * 
 */
@SuppressWarnings("serial")
public abstract class MultiFileUpload extends CssLayout implements DropHandler {

    private CssLayout progressBars = new CssLayout();
    private CssLayout uploads = new CssLayout();
    private String uploadButtonCaption = "...";

    /** text of the drop zone area */
    private String areaText = "<small>DROP<br/>FILES</small>";
    
    /** number of pending files */
    private int pendingFilesNo = 0;
    /** indicates if a file upload is in process */
    private boolean isInProcess = false;
    
    /** all registered {@link UploadActionListener} */
    private List<UploadActionListener> uploadListeners = new Vector<UploadActionListener>();

    public MultiFileUpload() {
        setWidth("200px");
        addComponent(progressBars);
        uploads.setStyleName("v-multifileupload-uploads");
        addComponent(uploads);
        prepareUpload();
    }

    /** Adds the specified {@link UploadActionListener}.
     *  All registered {@link UploadActionListener} will be inform about file upload actions.
     *  Please do also remove your listener to preventing memory leaks.<p />
     *  If the listener is already registered nothing will be do. 
     * @param l the listener to add
     */
    public void addUploadActionListener(UploadActionListener l) {
      if (!uploadListeners.contains(l)) uploadListeners.add(l);
    }
    
    /** Removes the specified {@link UploadActionListener}.
     *  The {@link UploadActionListener} will be not longer informed about file upload actions.
     *  If the listener is not registered nothing will be do. 
     * @param l the listener to remove
     */
    public void removeUploadActionListener(UploadActionListener l) {
      uploadListeners.remove(l);
    }
    
    /** Notified all registered {@link UploadActionListener} about a new started upload process.
     */
    private void notifyUploadStart(String file, int pendingFiles) {
      for (UploadActionListener l : uploadListeners) {
        l.fileUploadStarted(file, pendingFiles);
      }
    }
    
    /** Notified all registered {@link UploadActionListener} about a finished upload process.
     */
    private void notifyUploadFinished(String file, int pendingFiles) {
      for (UploadActionListener l : uploadListeners) {
        l.fileUploadFinished(file, pendingFiles);
      }
    }
    
    /** Notified all registered {@link UploadActionListener} about a aborted upload process.
     */
    private void notifyUploadError(String file, int pendingFiles) {
      for (UploadActionListener l : uploadListeners) {
        l.fileUploadError(file, pendingFiles);
      }
    }
    
    private void prepareUpload() {
        final FileBuffer receiver = createReceiver();

        final MultiUpload upload = new MultiUpload();
        MultiUploadHandler handler = new MultiUploadHandler() {
            private LinkedList<ProgressIndicator> indicators;

            public void streamingStarted(StreamingStartEvent event) {
              // issue #10: update file process informations
              isInProcess = true;
              pendingFilesNo--;
              
              notifyUploadStart(event.getFileName(), pendingFilesNo);
            }

            public void streamingFinished(StreamingEndEvent event) {
                if (!indicators.isEmpty()) {
                    progressBars.removeComponent(indicators.remove(0));
                }
                File file = receiver.getFile();
                handleFile(file, event.getFileName(), event.getMimeType(),
                        event.getBytesReceived());
                receiver.setValue(null);
                
                // issue #10: update file process informations
                isInProcess = false;
                
                notifyUploadFinished(event.getFileName(), pendingFilesNo);
            }

            public void streamingFailed(StreamingErrorEvent event) {
                Logger.getLogger(getClass().getName()).log(Level.FINE,
                        "Streaming failed", event.getException());

                for (ProgressIndicator progressIndicator : indicators) {
                    progressBars.removeComponent(progressIndicator);
                }
                
                // issue #10: update file process informations
                isInProcess = false;
                
                notifyUploadError(event.getFileName(), pendingFilesNo);
            }

            public void onProgress(StreamingProgressEvent event) {
                long readBytes = event.getBytesReceived();
                long contentLength = event.getContentLength();
                float f = (float) readBytes / (float) contentLength;
                indicators.get(0).setValue(f);
                
                // issue #10: update file process informations
                isInProcess = true;
            }

            public OutputStream getOutputStream() {
                FileDetail next = upload.getPendingFileNames().iterator()
                        .next();
                return receiver.receiveUpload(next.getFileName(),
                        next.getMimeType());
            }

            public void filesQueued(Collection<FileDetail> pendingFileNames) {
              // issue #10: update file process informations
              if (pendingFileNames == null) pendingFilesNo = 0;
              else pendingFilesNo = pendingFileNames.size();
              
              
                if (indicators == null) {
                    indicators = new LinkedList<ProgressIndicator>();
                }
                for (FileDetail f : pendingFileNames) {
                    ProgressIndicator pi = createProgressIndicator();
                    progressBars.addComponent(pi);
                    pi.setCaption(f.getFileName());
                    pi.setVisible(true);
                    indicators.add(pi);
                }
            }
        };
        upload.setHandler(handler);
        upload.setButtonCaption(getUploadButtonCaption());
        uploads.addComponent(upload);

    }

    private ProgressIndicator createProgressIndicator() {
        ProgressIndicator progressIndicator = new ProgressIndicator();
        progressIndicator.setPollingInterval(300);
        progressIndicator.setValue(0);
        return progressIndicator;
    }

    public String getUploadButtonCaption() {
        return uploadButtonCaption;
    }

    public void setUploadButtonCaption(String uploadButtonCaption) {
        this.uploadButtonCaption = uploadButtonCaption;
        Iterator<Component> componentIterator = uploads.getComponentIterator();
        while (componentIterator.hasNext()) {
            Component next = componentIterator.next();
            if (next instanceof MultiUpload) {
                MultiUpload upload = (MultiUpload) next;
                if (upload.isVisible()) {
                    upload.setButtonCaption(getUploadButtonCaption());
                }
            }
        }
    }

    private FileFactory fileFactory;

    public FileFactory getFileFactory() {
        if (fileFactory == null) {
            fileFactory = new TempFileFactory();
        }
        return fileFactory;
    }

    public void setFileFactory(FileFactory fileFactory) {
        this.fileFactory = fileFactory;
    }

    protected FileBuffer createReceiver() {
        FileBuffer receiver = new FileBuffer(FieldType.FILE) {
            @Override
            public FileFactory getFileFactory() {
                return MultiFileUpload.this.getFileFactory();
            }
        };
        return receiver;
    }

    protected int getPollinInterval() {
        return 500;
    }

    @Override
    public void attach() {
        super.attach();
        if (supportsFileDrops()) {
            prepareDropZone();
        }
    }

    private DragAndDropWrapper dropZone;
    
    /** indicates if the drop zone should be visible or not */
    private boolean dropZoneVisible = true;

    /** Returns the dropZoneVisible attribute.
     * @return the dropZoneVisible.
     */
    public boolean isDropZoneVisible() {
      return dropZoneVisible;
    }

    /** Sets the dropZoneVisible attribute to the specified value.
     * @param dropZoneVisible the new dropZoneVisible value
     */
    public void setDropZoneVisible(boolean dropZoneVisible) {
      this.dropZoneVisible = dropZoneVisible;
    }

    /** Returns if a file upload is in progress or if there are files are pending.
     * @return true if a file upload is in progress or if there are files are pending
     */
    public boolean isInProcess() {
      return (isInProcess || pendingFilesNo > 0);
    }

    /**
     * Sets up DragAndDropWrapper to accept multi file drops.
     */
    private void prepareDropZone() {
        if (dropZone == null && isDropZoneVisible()) {
            Component label = new Label(getAreaText(), Label.CONTENT_XHTML);
            label.setSizeUndefined();
            dropZone = new DragAndDropWrapper(label);
            dropZone.setStyleName("v-multifileupload-dropzone");
            dropZone.setSizeUndefined();
            addComponent(dropZone, 1);
            dropZone.setDropHandler(this);
            addStyleName("no-horizontal-drag-hints");
            addStyleName("no-vertical-drag-hints");
        }
    }

    /** Returns the area text of the drop zone.
     * @return area text of the drop zone
     */
    public String getAreaText() {
        return areaText;
    }

    /** Sets the area text of the drop zone.
     * @param areaText area text of the drop zone; can be contains HTML code; sample: 
     *  "<pre><small>DROP<br/>FILES</small></pre>"
     */
    public void setAreaText(String areaText) {
        this.areaText = areaText;
    }

    protected boolean supportsFileDrops() {
        AbstractWebApplicationContext context = (AbstractWebApplicationContext) getApplication()
                .getContext();
        WebBrowser browser = context.getBrowser();
        if (browser.isChrome()) {
            return true;
        } else if (browser.isFirefox()) {
            return true;
        } else if (browser.isSafari()) {
            return true;
        }
        return false;
    }

    abstract protected void handleFile(File file, String fileName,
            String mimeType, long length);

    /**
     * A helper method to set DirectoryFileFactory with given pathname as
     * directory.
     * 
     * @param file
     */
    public void setRootDirectory(String directoryWhereToUpload) {
        setFileFactory(new DirectoryFileFactory(
                new File(directoryWhereToUpload)));
    }

    public AcceptCriterion getAcceptCriterion() {
        // TODO accept only files
        // return new And(new TargetDetailIs("verticalLocation","MIDDLE"), new
        // TargetDetailIs("horizontalLoction", "MIDDLE"));
        return AcceptAll.get();
    }

    public void drop(DragAndDropEvent event) {
        DragAndDropWrapper.WrapperTransferable transferable = (WrapperTransferable) event
                .getTransferable();
        Html5File[] files = transferable.getFiles();
        for (final Html5File html5File : files) {
            final ProgressIndicator pi = new ProgressIndicator();
            pi.setCaption(html5File.getFileName());
            progressBars.addComponent(pi);
            final FileBuffer receiver = createReceiver();
            html5File.setStreamVariable(new StreamVariable() {

                private String name;
                private String mime;

                public OutputStream getOutputStream() {
                    return receiver.receiveUpload(name, mime);
                }

                public boolean listenProgress() {
                    return true;
                }

                public void onProgress(StreamingProgressEvent event) {
                    float p = (float) event.getBytesReceived()
                            / (float) event.getContentLength();
                    pi.setValue(p);
                }

                public void streamingStarted(StreamingStartEvent event) {
                    name = event.getFileName();
                    mime = event.getMimeType();

                }

                public void streamingFinished(StreamingEndEvent event) {
                    progressBars.removeComponent(pi);
                    handleFile(receiver.getFile(), html5File.getFileName(),
                            html5File.getType(), html5File.getFileSize());
                    receiver.setValue(null);
                }

                public void streamingFailed(StreamingErrorEvent event) {
                    progressBars.removeComponent(pi);
                }

                public boolean isInterrupted() {
                    return false;
                }
            });
        }

    }
    
}
