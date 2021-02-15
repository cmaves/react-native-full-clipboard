package com.reactnativefullclipboard

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import com.facebook.react.bridge.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

import java.util.ArrayDeque;

class FullClipboardModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "FullClipboard"
    }


    private class RNClip(data: ByteArray, mime: String) {
      val data  = data;
      val mime = mime;
      fun getMap(): WritableMap {
        val map = Arguments.createMap();
        map.putString("mime", mime);

        val bytes = Arguments.createArray();
        for (b in data) {
          bytes.pushInt(b.toInt());
        }
        map.putArray("dataArray", bytes);
        return map;
      }
      companion object {
        fun fromMap(map: ReadableMap): RNClip? {
          val mime = map.getString("mime")?: return null
          val array = map.getArray("dataArray")?: return null
          val data = ByteArray(array.size())
          for (i in 0 until array.size()) {
            data[i] = array.getInt(i).toByte()
          }
          return RNClip(data, mime)
        }
      }

    }
    private class Listener(reactContext: ReactApplicationContext, mimesArray: ReadableArray) : ClipboardManager.OnPrimaryClipChangedListener {
      private val mimes: MutableList<String> = mutableListOf<String>();
      init {
        for (i in 0 until mimesArray.size()) {
            val s = mimesArray.getString(i)?: continue;
            mimes.add(s);
        }
      }
      private val promises = ArrayDeque<Promise>();
      private val cr = reactContext.getContentResolver();
      var lastClip: RNClip? = null;
      var curClip: RNClip? = null;

      private val manager = reactContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;
      init {
        manager.addPrimaryClipChangedListener(this);
      }

      fun getNextClip(promise: Promise) {
        val cur = curClip;
        if (cur != null) {
          curClip = null;
          promise.resolve(cur.getMap());
        } else {
          promises.add(promise);
        }
      }

      fun updateClipboard(): RNClip? {
        if (mimes.isEmpty() || !manager.hasPrimaryClip()) { return null }

        val clipData: ClipData = manager.getPrimaryClip() ?: return null;
        dataItem@ for (i in 0 until clipData.itemCount) {
          val item = clipData.getItemAt(i);
          val uri = item.getUri();
          if (uri != null) {
            val mimeType = cr.getType(uri);
            if (mimeType != null) {
              if (mimes.binarySearch(mimeType) != -1) {
                var pfd: ParcelFileDescriptor? = null
                try {
                  pfd = cr.openFileDescriptor(uri, "r")?: return null
                  val size = pfd.getStatSize().toInt()
                  val fis = FileInputStream(pfd.getFileDescriptor())
                  val bytes = ByteArray(size)
                  var read = 0
                  while (read < size) {
                    val gotten = fis.read(bytes, read, size - read)
                    if (gotten == -1) {
                      Log.wtf("FullClipboardModule.updateClipboard", "File had no bytes left!!!!!");
                      continue@dataItem
                    }
                    read += gotten
                  }
                  return RNClip(bytes, mimeType)
                } catch (e: Exception) {
                  Log.w("FullClipboardModule.updateClipboard", e);
                  continue
                } finally {
                  if (pfd != null) {
                    pfd.close()
                  }
                }
              }
            }
          }
          val cs = item.getText()?: continue;
          val new = RNClip(cs.toString().toByteArray(), "text/plain;charset=utf-8");

          return new;
        }
        return null;
      }

      private fun resolvePromise(clip: RNClip) {
        if (lastClip == clip) {
          return;
        }
        lastClip = clip;
        if (promises.isNotEmpty()) {
          val promise = promises.pop()
          promise.resolve(clip.getMap());
        } else {
          curClip = clip;
        }
      }

      override fun onPrimaryClipChanged() {
        val clip = updateClipboard()?: return;
        resolvePromise(clip);
      }
      fun close() {
        manager.removePrimaryClipChangedListener(this);
        for (promise in promises) {
            promise.reject("Listener closed");
        }
      }
    }
    // Example method
    // See https://reactnative.dev/docs/native-modules-android
    @ReactMethod
    fun multiply(a: Int, b: Int, promise: Promise) {

      promise.resolve(a * b)

    }

    private val clipboardMap = hashMapOf<Int, Listener>();
    private var counter = 0;
    private val context = reactContext;

    @ReactMethod
    fun newListener(mimes: ReadableArray, promise: Promise) {
       while (clipboardMap.containsKey(counter)) {
            counter += 1;
       }
       clipboardMap.put(counter, Listener(context, mimes));
       promise.resolve(counter.toDouble());
    }

    @ReactMethod
    fun getNextClip(i: Double, promise: Promise) {
        val listener = clipboardMap.get(i.toInt());
        if (listener == null) {
            promise.reject("Invalid listener");
            return;
        };
        listener.getNextClip(promise);
    }
    @ReactMethod
    fun getCurClip(i: Double, promise: Promise) {
      val listener = clipboardMap.get(i.toInt());
      if (listener == null) {
        promise.reject("Invalid listener");
        return;
      };
      var clip = listener.updateClipboard();
      if (clip == null) {
        promise.reject("Clip did not match supported MIMEs");
        return;
      }
      promise.resolve(clip.getMap());

    }
    @ReactMethod
    fun destroyListener(i: Double) {
        val l = clipboardMap.remove(i.toInt())?: return;
        l.close();
    }
    var tempFile: File? = null
   @ReactMethod
   fun setClipboard(map: ReadableMap, promise: Promise) {
     val clip = RNClip.fromMap(map)
     if (clip == null) {
       promise.reject("Invalid object received")
       return;
     }
     val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;
     if (clip.mime.startsWith("text/plain")) {
       val charStr = clip.mime.substring(10);
       val s = when(charStr) {
         "", ";charset=utf-8" -> clip.data.toString(Charsets.UTF_8)
         ";charset=utf-16le" -> clip.data.toString(Charsets.UTF_16LE)
         ";charset=utf-16be" -> clip.data.toString(Charsets.UTF_16BE)
         else -> {
           promise.reject("Unknown character encoding of text/plain")
           return;
         }
       }
       val cData = ClipData.newPlainText(null, s)
       manager.setPrimaryClip(cData)
       promise.resolve(null)
       val toDelete = tempFile
       tempFile = null
       if (toDelete != null) {
         toDelete.delete()
       }
       return
     }
     val ext = when (clip.mime) {
       "image/png" -> ".png"
       "image/jpeg" -> ".jpeg"
       else -> ".tmp"
     }
     var fos: FileOutputStream? = null;
     val temp = try {
       val clipDir = File(context.cacheDir, "clips");
       clipDir.mkdir();
       val temp = File.createTempFile("clip-", ext, clipDir);
       fos = FileOutputStream(temp)
       fos.write(clip.data)
       val uri = FileProvider.getUriForFile(context, "com.reactnativefullclipboard.FileProvider", temp)
       val cData = ClipData.newUri(context.contentResolver, null, uri)
       manager.setPrimaryClip(cData)
       temp
     } catch(e: Exception) {
        promise.reject(e)
        return
     } finally {
       if (fos != null) {
         fos.close()
       }
     }
     val toDelete = tempFile
     tempFile = temp
     promise.resolve(null)
     if (toDelete != null) {
       toDelete.delete()
     }
   }
}
