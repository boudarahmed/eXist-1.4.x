/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2010 The eXist Project
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 *  $Id$
 */
package org.exist.ant;

import java.io.File;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.DirSet;

import org.exist.backup.Restore;

/**
 * @author wolf
 */
public class RestoreTask extends AbstractXMLDBTask {

    private File zipFile = null;
    private File dir = null;
    private DirSet dirSet = null;
    private String restorePassword = null;

    /* (non-Javadoc)
     * @see org.apache.tools.ant.Task#execute()
     */
    public void execute() throws BuildException {
        if (uri == null) {
            throw new BuildException("You have to specify an XMLDB collection URI");
        }

        if (dir == null && dirSet == null && zipFile == null) {
            throw new BuildException("Missing required argument: either dir, dirset or file required");
        }

        if (dir != null && !dir.canRead()) {
            String msg = "Cannot read restore file: " + dir.getAbsolutePath();
            if (failonerror) {
                throw new BuildException(msg);
            } else {
                log(msg, Project.MSG_ERR);
            }

        } else {
            registerDatabase();
            try {
                if (dir != null) {
                    log("Restoring from " + dir.getAbsolutePath(), Project.MSG_INFO);
                    File file = new File(dir, "__contents__.xml");
                    if (!file.exists()) {
                        String msg = "Did not found file " + file.getAbsolutePath();
                        if (failonerror) {
                            throw new BuildException(msg);
                        } else {
                            log(msg, Project.MSG_ERR);
                        }
                    } else {
                        Restore restore = new Restore(user, password, restorePassword, file, uri);
                        restore.restore(false, null);
                    }

                } else if (dirSet != null) {
                    DirectoryScanner scanner = dirSet.getDirectoryScanner(getProject());
                    scanner.scan();
                    String[] includedFiles = scanner.getIncludedFiles();
                    log("Found " + includedFiles.length + " files.\n");

                    for (String included : includedFiles) {
                        dir = new File(scanner.getBasedir() + File.separator + included);
                        File contentsFile = new File(dir, "__contents__.xml");
                        if (!contentsFile.exists()) {
                            String msg = "Did not found file " + contentsFile.getAbsolutePath();
                            if (failonerror) {
                                throw new BuildException(msg);
                            } else {
                                log(msg, Project.MSG_ERR);
                            }
                        } else {
                            log("Restoring from " + contentsFile.getAbsolutePath() + " ...\n");
                            // TODO subdirectories as sub-collections?
                            Restore restore = new Restore(user, password, restorePassword, contentsFile, uri);
                            restore.restore(false, null);
                        }
                    }

                } else if (zipFile != null) {
                    log("Restoring from " + zipFile.getAbsolutePath(), Project.MSG_INFO);
                    if (!zipFile.exists()) {
                        String msg = "File not found: " + zipFile.getAbsolutePath();
                        if (failonerror) {
                            throw new BuildException(msg);
                        } else {
                            log(msg, Project.MSG_ERR);
                        }
                    } else {
                        Restore restore = new Restore(user, password, restorePassword, zipFile, uri);
                        restore.restore(false, null);
                    }
                }
                
            } catch (Exception e) {
                e.printStackTrace();
                String msg = "Exception during restore: " + e.getMessage();
                if (failonerror) {
                    throw new BuildException(msg, e);
                } else {
                    log(msg, e, Project.MSG_ERR);
                }
            }
        }
    }

    public DirSet createDirSet() {
        this.dirSet = new DirSet();
        return dirSet;
    }

    /**
     * @param dir
     */
    public void setDir(File dir) {
        this.dir = dir;
    }

    public void setFile(File file) {
        this.zipFile = file;
    }

    public void setRestorePassword(String pass) {
        this.restorePassword = pass;
    }
}
