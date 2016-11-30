package com.hanhuy.android.keepshare

import android.os.Bundle
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.common.api.GoogleApiClient.{ConnectionCallbacks, OnConnectionFailedListener}
import com.google.android.gms.drive.{Drive, DriveContents, DriveFile, MetadataChangeSet}
import com.google.android.gms.drive.query.{Filters, Query, SearchableField}
import com.hanhuy.android.common.{Futures, Logcat, ManagedResource, UiBus}

import collection.JavaConversions._
import java.security.{MessageDigest, SecureRandom}
import java.nio.ByteBuffer

import android.app.Activity
import android.content.Context
import java.io.{ByteArrayOutputStream, IOException}

import android.widget.Toast

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._
import scala.util.Try
import Futures._
import ManagedResource._
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.io.CipherOutputStream
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.{KeyParameter, ParametersWithIV}

object KeyManager {
  type Key = Array[Byte]
  val VERIFIER = "KeepShare Verifier"

  val STATE_SAVE = "save"
  val STATE_LOAD = "load"

  val EXTRA_STATE = "com.hanhuy.android.keepshare.extra.STATE"

  val KEY_FILE = "keepshare.key"

  lazy val sha1 = MessageDigest.getInstance("SHA1")
  def sha1(b: Array[Byte]): String = hex(sha1.digest(b))

  private var _cloudFutureKey = Option.empty[Future[Key]]

  def clear() {
    // Should clear be run on session timeout? Otherwise cloud key stays in RAM
    _cloudFutureKey = None
  }

  // prefix with 01 and strip it off, otherwise
  // prefixed 00's will be dropped from output
  // this inserts a sign byte at the beginning
  // if the first byte is > 0x7f strip it off
  def bytes(hex: String) =
    BigInt("01" + hex, 16).toByteArray.takeRight(hex.length / 2)
  //def bytes(hex: String): Array[Byte] =
  //  hex.grouped(2).map (Integer.parseInt(_, 16).asInstanceOf[Byte]).toArray

  def hex(b: Array[Byte]) =
    b.map { byte => "%02X" format (byte & 0xff) }.mkString

  val random = new SecureRandom
  val ALG = "AES"
  val CIPHER_ALG = ALG + "/CBC/PKCS5Padding"

  /** @return a hex string iv:encrypted
    */
  def encrypt(k: Key, data: Array[Byte]): String = {
    val cipher = blockCipher
    val iv = Array.ofDim[Byte](16)
    random.nextBytes(iv)
    cipher.init(true, ivParameter(keyParameter(k), iv))
    hex(iv) + ":" + hex(processCipher(cipher, data))
  }

  def encrypt(k: Key, data: String): String =
    encrypt(k, data.getBytes("utf-8"))

  def aes = new AESEngine
  def keyParameter(key: Array[Byte]) = new KeyParameter(key)
  def ivParameter(key: KeyParameter, iv: Array[Byte]) = new ParametersWithIV(key, iv)
  def blockCipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(aes))

  /** @param data iv:encrypted in hex
    * @return a byte array
    */
  def decrypt(key: Key, data: String): Array[Byte] = {
    data.split(":") match {
      case Array(ivs, encs) =>
        val iv = bytes(ivs)
        val encrypted = bytes(encs)
        val cipher = blockCipher
        cipher.init(false, ivParameter(keyParameter(key), iv))
        processCipher(cipher, encrypted)
      case _ =>
        val e = new RuntimeException("match error")
        Application.logException(data, e)
        throw e
    }
  }

  def processCipher(c: BufferedBlockCipher, data: Array[Byte]): Array[Byte] = {
    val bout = new ByteArrayOutputStream
    val cout = new CipherOutputStream(bout, c)
    cout.write(data, 0, data.length)
    cout.close()
    bout.toByteArray
  }

  def decryptToString(k: Key, data: String): String =
    new String(decrypt(k, data), "utf-8")
}
class KeyManager(c: Context, settings: Settings) {
  import RequestCodes._

  import KeyManager._
  val log = Logcat("KeyManager")

//  def accountName = credential.getSelectedAccountName
//  def accountName_=(a: String) = {
//    nameSelected = true
//    credential.setSelectedAccountName(a)
//  }

  def ready = (Seq(
    Settings.DATABASE_FILE,
    Settings.PASSWORD,
    Settings.KEYFILE_PATH,
    Settings.VERIFY_DATA) forall (settings.get(_) != null)) &&
    (!settings.get(Settings.NEEDS_PIN) || PINHolderService.instance.isDefined)

  def config: Future[Either[KeyError,(String,String,String)]] = {
    localKey map {
      case Left(error) => Left(error)
      case Right(key) =>
        val db = KeyManager.decryptToString(
          key, settings.get(Settings.DATABASE_FILE))
        val pw = KeyManager.decryptToString(
          key, settings.get(Settings.PASSWORD))
        val keyf = KeyManager.decryptToString(
          key, settings.get(Settings.KEYFILE_PATH))
        val verifier = KeyManager.decryptToString(
          key, settings.get(Settings.VERIFY_DATA))
        if (verifier != KeyManager.VERIFIER)
          Left(KeyError.VerifyFailure(c.getString(R.string.failed_verify)))
        else
          Right((db, pw, keyf))
    }
  }

  lazy val googleClient = {
    val client = new GoogleApiClient.Builder(c)
      .addApi(Drive.API)
      .addScope(Drive.SCOPE_APPFOLDER)
      .build
    client.registerConnectionCallbacks(new ConnectionCallbacks {
      override def onConnectionSuspended(i: Int) = {}

      override def onConnected(bundle: Bundle) = {
        if (!clientPromise.isCompleted)
          clientPromise.success(client)
      }
    })
    client.registerConnectionFailedListener(new OnConnectionFailedListener {
      override def onConnectionFailed(r: ConnectionResult) = {
        if (r.getErrorCode == ConnectionResult.RESOLUTION_REQUIRED || r.getErrorCode == ConnectionResult.SIGN_IN_REQUIRED) {
          c match {
            case a: Activity => r.startResolutionForResult(a, RequestCodes.REQUEST_SIGN_IN)
            case _ => ()
          }
        }
      }
    })
    client
  }
  val clientPromise = Promise[GoogleApiClient]()
  def makeApiClient() = {
    googleClient.connect()
  }
  lazy val apiClient = {
    makeApiClient()
    Await.result(clientPromise.future, Duration.Inf)
  }

  private def _loadKey(): Key = {
    val appFolder = Drive.DriveApi.getAppFolder(apiClient)
    try {
      val result = appFolder.queryChildren(apiClient,
        new Query.Builder()
          .addFilter(Filters.eq(SearchableField.TITLE, KEY_FILE))
          .build())
        .await().getMetadataBuffer
      implicit val dfileCloser = new ResourceManager[DriveContents] {
        override def dispose(resource: DriveContents) = resource.discard(apiClient)
      }
      val k = result find (_ != null) map { metadata =>
        val buf = Array.ofDim[Byte](32)
        val b = ByteBuffer.allocate(32)
        for {
          dfile <- using(Drive.DriveApi.getFile(apiClient, metadata.getDriveId)
            .open(apiClient, DriveFile.MODE_READ_ONLY, null)
            .await().getDriveContents)
          in <- using(dfile.getInputStream)
        } {
          Stream.continually(in.read(buf)).takeWhile(r => r != -1 && b.remaining >= r) foreach { r =>
            b.put(buf, 0, r)
          }
        }
        b.flip()
        if (b.remaining != 32) {
          log.e("wrong buffer size: " + b.remaining)
          throw KeyError.LoadFailed("wrong buffer size: " + b.remaining)
        }
        b.get(buf)
        val hash = settings.get(Settings.CLOUD_KEY_HASH)
        if (hash != null && sha1(buf) != hash) {
          log.e("cloud key has changed")
          throw KeyError.LoadFailed("cloud key has changed")
        }
        log.v("Loaded cloud key")
        buf
      } getOrElse createKey()
      result.release()
      k
    } catch {
      case e: UserRecoverableAuthException =>
        UiBus.post {
          Toast.makeText(Application.instance,
            Application.instance.getString(
              R.string.toast_unable_load_key, e.getMessage),
            Toast.LENGTH_SHORT).show()
        }
        requestAuthz(e, STATE_LOAD)
        throw KeyError.LoadFailed("need authz")
      case ex: IOException =>
        log.e("IO error loading cloud key", ex)
        UiBus.post {
          Toast.makeText(Application.instance,
            Application.instance.getString(
              R.string.toast_unable_load_key, ex.getMessage),
            Toast.LENGTH_SHORT).show()
        }
        _loadKey()
    }
  }

  def fetchCloudKey(): Future[Key] = {
    val p = KeyManager._cloudFutureKey getOrElse {
      Future { _loadKey() }
    }
    KeyManager._cloudFutureKey = Some(p)

    p
  }

  def createKey(): Key = {
    try {

      val keybuf = Array.ofDim[Byte](32)
      random.nextBytes(keybuf)
      settings.set(Settings.CLOUD_KEY_HASH, sha1(keybuf))
      val appFolder = Drive.DriveApi.getAppFolder(apiClient)
      val metadata = new MetadataChangeSet.Builder()
        .setMimeType("application/octet-stream")
        .setTitle(KEY_FILE).build()
      val contents = Drive.DriveApi.newDriveContents(apiClient).await().getDriveContents
      for {
        out <- using(contents.getOutputStream)
      } {
        val written = out.write(keybuf)
      }
      appFolder.createFile(apiClient, metadata, contents)
      _loadKey()
    } catch {
      case e: UserRecoverableAuthException =>
        requestAuthz(e, STATE_SAVE)
        throw KeyError.CreateFailed("need authz")
    }
  }

  def localKey: Future[Either[KeyError,Key]] = {
    fetchCloudKey() map { ck =>

      val k = settings.get(Settings.LOCAL_KEY)

      val needsPin = settings.get(Settings.NEEDS_PIN)
      if (needsPin && PINHolderService.instance.isEmpty) {
        Left(KeyError.NeedPin)
      } else if (k == null) {
        val keybuf = Array.ofDim[Byte](32)
        random.nextBytes(keybuf)

        val saved = PINHolderService.instance map { s =>
          bytes(encrypt(s.pinKey, keybuf))
        } getOrElse keybuf

        val k = encrypt(ck, saved)

        settings.set(Settings.LOCAL_KEY, k)
        Right(keybuf)
      } else {
        val okey = Try(decrypt(ck, k)).toOption
        val actual = (for {
          s <- PINHolderService.instance
          key <- okey
        } yield {
          val st = new String(key, "utf-8")
          decrypt(s.pinKey, st)
        }) orElse okey
        actual map { a =>
          Right(a)
        } getOrElse Left(KeyError.NeedClear)
      }
    }
  }

  private def requestAuthz(e: UserRecoverableAuthException, state: String) {
    val i = e.getIntent
    i.putExtra(EXTRA_STATE, state)
    c match {
      case a: Activity =>
        a.startActivityForResult(i, REQUEST_AUTHORIZATION)
      case _ =>
    }
  }
}

sealed trait KeyError
object KeyError {
  case object NeedPin extends Exception("Need PIN") with KeyError
  case class VerifyFailure(error: String) extends KeyError
  case class AuthFailure(error: String) extends Exception(error) with KeyError
  case object NeedLoad extends Exception("Need to load") with KeyError
  case object NeedClear extends KeyError
  case object NeedSetup extends Exception("Need setup") with KeyError
  case class LoadFailed(s: String) extends Exception(s) with KeyError
  case class CreateFailed(s: String) extends Exception(s) with KeyError
  case object NotReady extends Exception("Not ready") with KeyError
}
