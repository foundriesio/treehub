package com.advancedtelematic.treehub.object_store

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.advancedtelematic.data.DataType.{ObjectId, TObject}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.treehub.db.ObjectRepositorySupport
import com.advancedtelematic.treehub.http.Errors
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class ObjectStore(blobStore: BlobStore)(implicit ec: ExecutionContext, db: Database) extends ObjectRepositorySupport {

  import scala.async.Async._

  def store(namespace: Namespace, id: ObjectId, blob: Source[ByteString, _]): Future[TObject] = {
    val obj = TObject(namespace, id, TObject.reserveSize)
    lazy val createF = objectRepository.create(obj)

    lazy val uploadF = async {
      val size = await(blobStore.store(namespace, id, blob))
      val newObj = obj.copy(byteSize = size)
      await(objectRepository.updateSize(namespace, id, size))
      newObj
    }.recoverWith {
      case e =>
        objectRepository.delete(namespace, id)
          .flatMap(_ => FastFuture.failed(e))
          .recoverWith { case _ => FastFuture.failed(e) }
    }

    createF.flatMap(_ => uploadF)
  }

  def isUploaded(namespace: Namespace, id: ObjectId): Future[Boolean] =
    for {
      dbUploaded <- objectRepository.isUploaded(namespace, id)
      fsExists <- blobStore.exists(namespace, id)
    } yield fsExists && dbUploaded

  def findBlob(namespace: Namespace, id: ObjectId): Future[(Long, HttpResponse)] = {
    for {
      _ <- ensureUploaded(namespace, id)
      tobj <- objectRepository.find(namespace, id)
      response <- blobStore.buildResponse(namespace, id)
    } yield (tobj.byteSize, response)
  }

  def readFull(namespace: Namespace, id: ObjectId): Future[ByteString] = {
    blobStore.readFull(namespace, id)
  }

  def usage(namespace: Namespace): Future[Long] = {
    objectRepository.usage(namespace)
  }

  private def ensureUploaded(namespace: Namespace, id: ObjectId): Future[ObjectId] = {
    isUploaded(namespace, id).flatMap {
      case true => Future.successful(id)
      case false => Future.failed(Errors.ObjectNotFound)
    }
  }
}
