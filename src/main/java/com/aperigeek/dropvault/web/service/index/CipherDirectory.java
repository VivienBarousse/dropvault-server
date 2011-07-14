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
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.SimpleFSDirectory;

/**
 *
 * @author Vivien Barousse
 */
public class CipherDirectory extends SimpleFSDirectory {

    private SecretKey key;
    
    public CipherDirectory(File path, SecretKey key) throws IOException {
        super(path);
        this.key = key;
    }

    /*
     * TODO: Performance issue!
     * The content of the file has to be decrypted to
     * determine with precision the actual file length...
     * Maybe something more efficient can be done instead.
     */
    @Override
    public long fileLength(String name) throws IOException {
        File file = new File(getDirectory(), name);
        return getByteBuffer(file).length;
    }

    @Override
    public IndexInput openInput(String name) throws IOException {
        File file = new File(getDirectory(), name);
        CipherIndexInput input = new CipherIndexInput(file);
        return input;
    }

    @Override
    public IndexInput openInput(String name, int bufferSize) throws IOException {
        return openInput(name);
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
        
        InputStream plain = new FileInputStream(file);
        InputStream in = new CipherInputStream(plain, getCipher(Cipher.DECRYPT_MODE));
        
        DynamicByteBuffer byteBuffer = new DynamicByteBuffer();
        byte[] src = new byte[2048];
        int readed;
        while ((readed = in.read(src)) != -1) {
            byteBuffer.put(src, 0, readed);
        }
        byteBuffer.setPosition(0);
        
        in.close();
        plain.close();
        
        return byteBuffer;
    }
    
    protected Cipher getCipher(int mode) {
        try {
            Cipher cipher = Cipher.getInstance("Blowfish");
            cipher.init(mode, key);
            return cipher;
        } catch (Exception ex) {
            throw new RuntimeException("Bad configuration", ex);
        }
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
            OutputStream plaout = new FileOutputStream(file);
            OutputStream out = new CipherOutputStream(plaout, getCipher(Cipher.ENCRYPT_MODE));
            out.write(byteBuffer.toByteArray());
            out.flush();
            out.close();
            plaout.close();
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
    
}
