/*-
 * Copyright (C) 2007-2014 Erik Larsson
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.takari.jdkget.osx;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Date;

import io.takari.jdkget.osx.dmg.udif.UDIFDetector;
import io.takari.jdkget.osx.dmg.udif.UDIFRandomAccessStream;
import io.takari.jdkget.osx.hfsexplorer.IOUtil;
import io.takari.jdkget.osx.hfsexplorer.Java7Util;
import io.takari.jdkget.osx.hfsexplorer.fs.AppleSingleBuilder;
import io.takari.jdkget.osx.hfsexplorer.fs.AppleSingleBuilder.AppleSingleVersion;
import io.takari.jdkget.osx.hfsexplorer.fs.AppleSingleBuilder.FileSystem;
import io.takari.jdkget.osx.hfsexplorer.fs.AppleSingleBuilder.FileType;
import io.takari.jdkget.osx.io.ReadableFileStream;
import io.takari.jdkget.osx.io.ReadableRandomAccessStream;
import io.takari.jdkget.osx.io.RuntimeIOException;
import io.takari.jdkget.osx.storage.fs.FSEntry;
import io.takari.jdkget.osx.storage.fs.FSFile;
import io.takari.jdkget.osx.storage.fs.FSFolder;
import io.takari.jdkget.osx.storage.fs.FSFork;
import io.takari.jdkget.osx.storage.fs.FSForkType;
import io.takari.jdkget.osx.storage.fs.FSLink;
import io.takari.jdkget.osx.storage.fs.FileSystemDetector;
import io.takari.jdkget.osx.storage.fs.FileSystemHandler;
import io.takari.jdkget.osx.storage.fs.FileSystemHandlerFactory;
import io.takari.jdkget.osx.storage.fs.FileSystemMajorType;
import io.takari.jdkget.osx.storage.fs.FileSystemHandlerFactory.CustomAttribute;
import io.takari.jdkget.osx.storage.io.DataLocator;
import io.takari.jdkget.osx.storage.io.ReadableStreamDataLocator;
import io.takari.jdkget.osx.storage.io.SubDataLocator;
import io.takari.jdkget.osx.storage.ps.Partition;
import io.takari.jdkget.osx.storage.ps.PartitionSystemDetector;
import io.takari.jdkget.osx.storage.ps.PartitionSystemHandler;
import io.takari.jdkget.osx.storage.ps.PartitionSystemHandlerFactory;
import io.takari.jdkget.osx.storage.ps.PartitionSystemType;
import io.takari.jdkget.osx.storage.ps.PartitionType;

/**
 * Command line program which extracts all or part of the contents of a
 * HFS/HFS+/HFSX file system to a specified path.
 *
 * @author <a href="http://www.catacombae.org/" target="_top">Erik Larsson</a>
 */
public class UnHFS {
  
  private static boolean debug = false;

  /**
   * UnHFS entry point. The main method's only responsibility is to parse and
   * validate program arguments. It then passes them on to the static method
   * unhfs(...), which contains the actual program logic.
   *
   * @param args program arguments.
   */
  public static void main(String[] args) {
    String outputDirname = ".";
    String fsRoot = "/";
    boolean extractFolderDirectly = true;
    boolean extractResourceForks = false;
    boolean verbose = false;
    int partitionNumber = -1; // -1 means search for first supported partition

    int i;
    for (i = 0; i < args.length; ++i) {
      String curArg = args[i];

      if (curArg.equals("-o")) {
        if (i + 1 < args.length)
          outputDirname = args[++i];
        else {
          //printUsage(System.err);
          System.exit(1);
        }
      } else if (curArg.equals("-fsroot")) {
        if (i + 1 < args.length)
          fsRoot = args[++i];
        else {
          //printUsage(System.err);
          System.exit(1);
        }
      } else if (curArg.equals("-create")) {
        extractFolderDirectly = false;
      } else if (curArg.equals("-resforks")) {
        if (i + 1 < args.length) {
          String value = args[++i];
          if (value.equalsIgnoreCase("NONE")) {
            extractResourceForks = false;
          } else if (value.equalsIgnoreCase("APPLEDOUBLE")) {
            extractResourceForks = true;
          } else {
            System.err.println("Error: Invalid value \"" + value +
              "\" for -resforks!");
            //printUsage(System.err);
            System.exit(1);
          }
        } else {
          //printUsage(System.err);
          System.exit(1);
        }
      } else if (curArg.equals("-partition")) {
        if (i + 1 < args.length) {
          try {
            partitionNumber = Integer.parseInt(args[++i]);
          } catch (NumberFormatException nfe) {
            System.err.println("Error: Invalid partition number \"" +
              args[i] + "\"!");
            //printUsage(System.err);
            System.exit(1);
          }
        } else {
          //printUsage(System.err);
          System.exit(1);
        }      
      } else if (curArg.equals("-v")) {
        verbose = true;
      } else if (curArg.equals("--")) {
        ++i;
        break;
      } else
        break;
    }

    if (i != args.length - 1) {
      //printUsage(System.err);
      System.exit(1);
    }

    String inputFilename = args[i];
    File inputFile = new File(inputFilename);
    if (!inputFile.isDirectory() &&
      !(inputFile.exists() && inputFile.canRead())) {
      System.err.println("Error: Input file \"" + inputFilename + "\" can not be read!");
      //printUsage(System.err);
      System.exit(1);
    }

    File outputDir = new File(outputDirname);
    if (!(outputDir.exists() && outputDir.isDirectory())) {
      System.err.println("Error: Invalid output directory \"" + outputDirname + "\"!");
      //printUsage(System.err);
      System.exit(1);
    }

    ReadableRandomAccessStream inputStream = new ReadableFileStream(inputFilename);

    try {
      UnHFS unHfs = new UnHFS();
      unHfs.unhfs(System.out, inputStream, outputDir, fsRoot, extractFolderDirectly, extractResourceForks, partitionNumber, verbose);
    } catch (RuntimeIOException e) {
      System.err.println("Exception while executing main routine:");
      e.printStackTrace();
    }
  }

  /**
   * The main routine in the program, which gets invoked after arguments
   * parsing is complete. The routine expects all arguments to be fully parsed
   * and valid.
   *
   * @param outputStream the PrintStream where all the messages will go
   * (should normally be System.out).
   * @param inFileStream the stream containing the file system data.
   * @param outputDir
   * @param fsRoot
   * @param password the password used to unlock an encrypted image.
   * @param extractFolderDirectly if fsRoot is a folder, extract directly into outputDir?
   * @param extractResourceForks
   * @param partitionNumber
   * @param verbose
   * @throws io.takari.jdkget.osx.io.RuntimeIOException
   */
  private void unhfs(
    PrintStream outputStream,
    ReadableRandomAccessStream inFileStream, 
    File outputDir,
    String fsRoot, 
    boolean extractFolderDirectly,
    boolean extractResourceForks, 
    int partitionNumber, 
    boolean verbose)
      throws RuntimeIOException {
   
    logDebug("Trying to detect UDIF structure...");
    if (UDIFDetector.isUDIFEncoded(inFileStream)) {
      UDIFRandomAccessStream stream = null;
      try {
        stream = new UDIFRandomAccessStream(inFileStream);
        inFileStream = stream;
      } catch (Exception e) {
        e.printStackTrace();
        System.err.println("Unhandled exception while trying to load UDIF wrapper.");
        System.exit(1);
      }
    }    

    DataLocator inputDataLocator = new ReadableStreamDataLocator(inFileStream);
    PartitionSystemType[] psTypes = PartitionSystemDetector.detectPartitionSystem(inputDataLocator, false);
    if (psTypes.length >= 1) {
      outer: for (PartitionSystemType chosenType : psTypes) {
        PartitionSystemHandlerFactory fact = chosenType.createDefaultHandlerFactory();
        PartitionSystemHandler psHandler = fact.createHandler(inputDataLocator);
        if (psHandler.getPartitionCount() > 0) {
          Partition[] partitionsToProbe;
          if (partitionNumber >= 0) {
            if (partitionNumber < psHandler.getPartitionCount()) {
              partitionsToProbe = new Partition[] {psHandler.getPartition(partitionNumber)};
            } else {
              break;
            }
          } else if (partitionNumber == -1) {
            partitionsToProbe = psHandler.getPartitions();
          } else {
            System.err.println("Invalid partition number: " + partitionNumber);
            System.exit(1);
            return;
          }
          for (Partition p : partitionsToProbe) {
            if (p.getType() == PartitionType.APPLE_HFS_CONTAINER) {
              inputDataLocator = new SubDataLocator(inputDataLocator, p.getStartOffset(), p.getLength());
              break outer;
            } else if (p.getType() == PartitionType.APPLE_HFSX) {
              inputDataLocator = new SubDataLocator(inputDataLocator, p.getStartOffset(), p.getLength());
              break outer;
            }
          }
        }
      }
    }

    FileSystemMajorType[] fsTypes = FileSystemDetector.detectFileSystem(inputDataLocator);
    FileSystemHandlerFactory fact = null;
    outer: for (FileSystemMajorType type : fsTypes) {
      switch (type) {
        case APPLE_HFS:
        case APPLE_HFS_PLUS:
        case APPLE_HFSX:
          fact = type.createDefaultHandlerFactory();
          break outer;
        default:
      }
    }

    if (fact == null) {
      System.err.println("No HFS file system found.");
      System.exit(1);
    }

    CustomAttribute posixFilenamesAttribute = fact.getCustomAttribute("POSIX_FILENAMES");
    if (posixFilenamesAttribute == null) {
      System.err.println("Unexpected: HFS-ish file system handler does " + "not support POSIX_FILENAMES attribute.");
      System.exit(1);
      return;
    }

    fact.getCreateAttributes().setBooleanAttribute(posixFilenamesAttribute, true);
    FileSystemHandler fsHandler = fact.createHandler(inputDataLocator);
    logDebug("Getting entry by posix path: \"" + fsRoot + "\"");
    FSEntry entry = fsHandler.getEntryByPosixPath(fsRoot);
    if (entry instanceof FSFolder) {
      FSFolder folder = (FSFolder) entry;
      File dirForFolder;
      String folderName = folder.getName();
      if (extractFolderDirectly || folderName.equals("/") || folderName.length() == 0) {
        dirForFolder = outputDir;
      } else {
        dirForFolder = getFileForFolder(outputDir, folder, verbose);
      }
      if (dirForFolder != null) {
        extractFolder(folder, dirForFolder, extractResourceForks, verbose);
      }
    } else if (entry instanceof FSFile) {
      FSFile file = (FSFile) entry;
      extractFile(file, outputDir, extractResourceForks, verbose);
    } else {
      System.err.println("Requested path is not a folder or a file!");
      System.exit(1);
    }
  }

  private static void setFileTimes(File file, FSEntry entry, String fileType) {
    Long createdTime = null;
    Long lastAccessedTime = null;
    Long lastModifiedTime = null;

    if (entry.getAttributes().hasCreateDate()) {
      createdTime = entry.getAttributes().getCreateDate().getTime();
    }

    if (entry.getAttributes().hasAccessDate()) {
      lastAccessedTime = entry.getAttributes().getAccessDate().getTime();
    }

    if (entry.getAttributes().hasModifyDate()) {
      lastModifiedTime = entry.getAttributes().getModifyDate().getTime();
    }

    boolean fileTimesSet = false;
    if (Java7Util.isJava7OrHigher()) {
      try {
        Java7Util.setFileTimes(file.getPath(),
          createdTime != null ? new Date(createdTime) : null,
          lastAccessedTime != null ? new Date(lastAccessedTime) : null,
          lastModifiedTime != null ? new Date(lastModifiedTime) : null);
        fileTimesSet = true;
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    if (!fileTimesSet && lastModifiedTime != null) {
      boolean setLastModifiedResult;

      if (lastModifiedTime < 0) {
        System.err.println("Warning: Can not set " + fileType + "'s " +
          "last modified timestamp to pre-1970 date " +
          new Date(lastModifiedTime) + " " + "(raw: " +
          lastModifiedTime + "). Setting to earliest possible " +
          "timestamp (" + new Date(0) + ").");

        lastModifiedTime = (long) 0;
      }

      setLastModifiedResult = file.setLastModified(lastModifiedTime);
      if (!setLastModifiedResult) {
        System.err.println("Warning: Failed to set last modified " +
          "timestamp (" + lastModifiedTime + ") for " +
          fileType + " \"" + file.getPath() + "\" after " +
          "extraction.");
      }
    }
  }

  private static void extractFolder(FSFolder folder, File targetDir,
    boolean extractResourceForks, boolean verbose) {
    boolean wasEmpty = targetDir.list().length == 0;
    for (FSEntry e : folder.listEntries()) {
      if (e instanceof FSFile) {
        FSFile file = (FSFile) e;
        extractFile(file, targetDir, extractResourceForks, verbose);
      } else if (e instanceof FSFolder) {
        FSFolder subFolder = (FSFolder) e;
        File subFolderFile = getFileForFolder(targetDir, subFolder, verbose);
        if (subFolderFile != null) {
          extractFolder(subFolder, subFolderFile, extractResourceForks, verbose);
        }
      } else if (e instanceof FSLink) {
        // We don't currently handle links.
      }
    }
    if (wasEmpty) {
      setFileTimes(targetDir, folder, "folder");
    }
  }

  private static void extractFile(FSFile file, File targetDir,
    boolean extractResourceForks, boolean verbose)
      throws RuntimeIOException {
    File dataFile = new File(targetDir, scrub(file.getName()));
    if (!extractRawForkToFile(file.getMainFork(), dataFile)) {
      System.err.println("Failed to extract data " + "fork to " + dataFile.getPath());
    } else {
      if (verbose) {
        System.out.println(dataFile.getPath());
      }
      setFileTimes(dataFile, file, "data file");
    }

    if (extractResourceForks) {
      FSFork resourceFork = file.getForkByType(FSForkType.MACOS_RESOURCE);
      if (resourceFork.getLength() > 0) {
        File resFile = new File(targetDir, "._" + scrub(file.getName()));
        if (!extractResourceForkToAppleDoubleFile(resourceFork, resFile)) {
          System.err.println("Failed to extract resource " + "fork to " + resFile.getPath());
        } else {
          if (verbose) {
            System.out.println(resFile.getPath());
          }
          setFileTimes(resFile, file, "resource fork AppleDouble file");
        }
      }
    }
  }

  private static File getFileForFolder(File targetDir, FSFolder folder,
    boolean verbose) {
    File folderFile = new File(targetDir, scrub(folder.getName()));
    if (folderFile.isDirectory() || folderFile.mkdir()) {
      if (verbose)
        System.out.println(folderFile.getPath());
    } else {
      System.err.println("Failed to create directory " + folderFile.getPath());
      folderFile = null;
    }
    return folderFile;
  }

  private static boolean extractRawForkToFile(FSFork fork, File targetFile) throws RuntimeIOException {
    FileOutputStream os = null;
    ReadableRandomAccessStream in = null;

    try {
      os = new FileOutputStream(targetFile);

      in = fork.getReadableRandomAccessStream();

      long extractedBytes = IOUtil.streamCopy(in, os, 128 * 1024);
      if (extractedBytes != fork.getLength()) {
        System.err.println("WARNING: Did not extract intended number of bytes to \"" +
          targetFile.getPath() + "\"! Intended: " + fork.getLength() +
          " Extracted: " + extractedBytes);
      }

      return true;
    } catch (FileNotFoundException fnfe) {
      return false;
    } catch (Exception ioe) {
      ioe.printStackTrace();
      return false;
      //throw new RuntimeIOException(ioe);
    } finally {
      if (os != null) {
        try {
          os.close();
        } catch (Exception e) {
        }
      }
      if (in != null) {
        try {
          in.close();
        } catch (Exception e) {
        }
      }
    }
  }

  private static boolean extractResourceForkToAppleDoubleFile(FSFork resourceFork, File targetFile) {
    FileOutputStream os = null;
    ReadableRandomAccessStream in = null;
    try {
      AppleSingleBuilder builder = new AppleSingleBuilder(FileType.APPLEDOUBLE,
        AppleSingleVersion.VERSION_2_0, FileSystem.MACOS_X);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();

      in = resourceFork.getReadableRandomAccessStream();
      long extractedBytes = IOUtil.streamCopy(in, baos, 128 * 1024);
      if (extractedBytes != resourceFork.getLength()) {
        System.err.println("WARNING: Did not extract intended number of bytes to \"" +
          targetFile.getPath() + "\"! Intended: " + resourceFork.getLength() +
          " Extracted: " + extractedBytes);
      }

      builder.addResourceFork(baos.toByteArray());

      os = new FileOutputStream(targetFile);
      os.write(builder.getResult());
      return true;
    } catch (FileNotFoundException fnfe) {
      return false;
    } catch (Exception ioe) {
      ioe.printStackTrace();
      return false;
      //throw new RuntimeIOException(ioe);
    } finally {
      if (os != null) {
        try {
          os.close();
        } catch (Exception e) {
        }
      }
      if (in != null) {
        try {
          in.close();
        } catch (Exception e) {
        }
      }
    }
  }

  /**
   * Scrubs away all control characters from a string and replaces them with '_'.
   * @param s the string to be processed.
   * @return a scrubbed string.
   */
  private static String scrub(String s) {
    char[] cdata = s.toCharArray();
    for (int i = 0; i < cdata.length; ++i) {
      if ((cdata[i] >= 0 && cdata[i] <= 31) ||
        (cdata[i] == 127)) {
        cdata[i] = '_';
      }
    }
    return new String(cdata);
  }

  private static void logDebug(String s) {
    if (debug)
      System.err.println("DEBUG: " + s);
  }
}
