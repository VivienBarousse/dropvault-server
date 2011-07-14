/*  
 * This file is part of dropvault.
 *
 * dropvault is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dropvault is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with dropvault.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aperigeek.dropvault.web.service.index;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.SimpleFSDirectory;

/**
 *
 * @author Vivien Barousse
 */
public class CipherDirectory extends SimpleFSDirectory {

    public CipherDirectory(File path) throws IOException {
        super(path);
    }

    @Override
    public IndexInput openInput(String name) throws IOException {
        File file = new File(getDirectory(), name);
        CipherIndexInput input = new CipherIndexInput(file);
        return input;
    }

    @Override
    public IndexOutput createOutput(String name) throws IOException {
        File file = new File(getDirectory(), name);
        return new CipherIndexOutput(file);
    }

    protected DynamicByteBuffer getByteBuffer(File file) throws IOException {
        if (!file.exists()) {
            return new DynamicByteBuffer();
        }
        
        InputStream in = new FileInputStream(file);
        
        DynamicByteBuffer byteBuffer = new DynamicByteBuffer((int) file.length());
        byte[] src = new byte[4096];
        int readed;
        while ((readed = in.read(src, 0, 4096)) != -1) {
            byteBuffer.put(src, 0, readed);
        }
        byteBuffer.setPosition(0);
        
        return byteBuffer;
    }

    public class CipherIndexInput extends IndexInput {
        
        private File file;

        private DynamicByteBuffer byteBuffer;

        public CipherIndexInput(File file) throws IOException {
            this.file = file;
            load();
        }

        @Override
        public void close() throws IOException {
            byteBuffer = null;
        }

        @Override
        public long getFilePointer() {
            return byteBuffer.getPosition();
        }

        @Override
        public void seek(long l) throws IOException {
            byteBuffer.setPosition((int) l);
        }

        @Override
        public long length() {
            return byteBuffer.getLength();
        }

        @Override
        public byte readByte() throws IOException {
            return byteBuffer.get();
        }

        @Override
        public void readBytes(byte[] buffer, int off, int len) throws IOException {
            byteBuffer.get(buffer, off, len);
        }
        
        protected void load() throws IOException {
            byteBuffer = getByteBuffer(file);
        }
        
        public void reload() throws IOException {
            load();
            System.out.println("Reloaded index");
        }
        
    }

    public class CipherIndexOutput extends IndexOutput {

        private File file;
        
        private DynamicByteBuffer byteBuffer;

        public CipherIndexOutput(File file) throws IOException {
            this.file = file;
            this.byteBuffer = getByteBuffer(file);
        }

        @Override
        public void flush() throws IOException {
            OutputStream out = new FileOutputStream(file);
            out.write(byteBuffer.toByteArray());
            out.close();
        }

        @Override
        public void close() throws IOException {
            flush();
            byteBuffer = null;
        }

        @Override
        public long getFilePointer() {
            return byteBuffer.getPosition();
        }

        @Override
        public void seek(long l) throws IOException {
            byteBuffer.setPosition((int) l);
        }

        @Override
        public long length() throws IOException {
            return byteBuffer.getLength();
        }

        @Override
        public void writeByte(byte b) throws IOException {
            byteBuffer.put(b);
        }

        @Override
        public void writeBytes(byte[] src, int off, int len) throws IOException {
            byteBuffer.put(src, off, len);
        }
    }
    
    public static class DynamicByteBuffer {
        
        public static final int INITIAL_SIZE = 4 * 1024; // 4k
        
        private byte[] buffer;
        
        private int length;
        
        private int position;

        public DynamicByteBuffer() {
            buffer = new byte[INITIAL_SIZE];
            length = 0;
            position = 0;
        }

        public DynamicByteBuffer(int size) {
            this();
            ensureCapacity(size);
        }
        
        public byte get() {
            return buffer[position++];
        }
        
        public void get(byte[] dest, int off, int len) {
            System.arraycopy(buffer, position, dest, off, len);
            position += len;
        }
        
        public void put(byte b) {
            ensureCapacity(position + 1);
            buffer[position++] = b;
            if (position > length) {
                length = position;
            }
        }
        
        public void put(byte[] src, int off, int len) {
            ensureCapacity(position + len);
            System.arraycopy(src, off, buffer, position, len);
            position += len;
            if (position > length) {
                length = position;
            }
        }

        public int getLength() {
            return length;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }
        
        public byte[] toByteArray() {
            byte[] byteArray = new byte[length];
            System.arraycopy(buffer, 0, byteArray, 0, length);
            return byteArray;
        }
        
        protected void ensureCapacity(int min) {
            if (min > buffer.length) {
                System.out.println("RESIZE!");
                int newLength = buffer.length;
                while (newLength > 0 && newLength < min) {
                    newLength *= 2;
                }
                if (newLength < 0) {
                    throw new RuntimeException("RAM file to big");
                }
                byte[] newBuffer = new byte[newLength];
                System.arraycopy(buffer, 0, newBuffer, 0, length);
                buffer = newBuffer;
            }
        }
        
    }
    
    private static class IndexInputRef {
        
        CipherIndexInput input;
        
        IndexInputRef next;

        public IndexInputRef(CipherIndexInput input) {
            this.input = input;
        }

        public IndexInputRef(CipherIndexInput input, IndexInputRef next) {
            this.input = input;
            this.next = next;
        }
        
    }
    
}
