/*-
 * Copyright (C) 2008 Erik Larsson
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

package io.takari.jdkget.osx.storage.io;

import io.takari.jdkget.osx.io.RandomAccessStream;
import io.takari.jdkget.osx.io.ReadableRandomAccessStream;
import io.takari.jdkget.osx.io.ReadableRandomAccessSubstream;
import io.takari.jdkget.osx.io.SynchronizedReadableRandomAccessStream;

/**
 * @author <a href="http://www.catacombae.org/" target="_top">Erik Larsson</a>
 */
public class ReadableStreamDataLocator extends DataLocator {
  private SynchronizedReadableRandomAccessStream backingStream;
  private boolean closed = false;

  public ReadableStreamDataLocator(ReadableRandomAccessStream sourceStream) {
    this.backingStream =
      new SynchronizedReadableRandomAccessStream(sourceStream);
  }

  @Override
  public ReadableRandomAccessStream createReadOnlyFile() {
    return new ReadableRandomAccessSubstream(backingStream);
  }

  @Override
  public RandomAccessStream createReadWriteFile() {
    throw new UnsupportedOperationException("Not supported for this implementation.");
  }

  @Override
  public boolean isWritable() {
    return false;
  }

  public SynchronizedReadableRandomAccessStream getBackingStream() {
    return backingStream;
  }

  @Override
  public synchronized void releaseResources() {
    if (closed) {
      throw new RuntimeException("Stream is already closed.");
    }

    this.backingStream.close();

    closed = true;
  }

  @Override
  public synchronized void finalize() throws Throwable {
    try {
      if (!closed) {
        close();
      }
    } finally {
      super.finalize();
    }
  }
}
