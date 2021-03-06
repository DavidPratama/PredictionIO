package io.prediction.commons.modeldata.mongodb

import io.prediction.commons.Config
import io.prediction.commons.MongoUtils._
import io.prediction.commons.modeldata.{ ItemRecScore, ItemRecScores }
import io.prediction.commons.settings.{ Algo, App, OfflineEval }

import com.mongodb.casbah.Imports._

/** MongoDB implementation of ItemRecScores. */
class MongoItemRecScores(cfg: Config, db: MongoDB) extends ItemRecScores with MongoModelData {
  val config = cfg
  val mongodb = db

  /** Indices and hints. */
  val queryIndex = MongoDBObject("uid" -> 1)

  def getByUid(uid: String)(implicit app: App, algo: Algo, offlineEval: Option[OfflineEval] = None): Option[ItemRecScore] = {
    val modelset = offlineEval map { _ => false } getOrElse algo.modelset
    val itemRecScoreColl = db(collectionName(algo.id, modelset))

    itemRecScoreColl.ensureIndex(queryIndex) // not needed here. it's called in after(), just safety measure in case after() is not called

    itemRecScoreColl.findOne(MongoDBObject("uid" -> idWithAppid(app.id, uid))).map(dbObjToItemRecScore(_, app.id))
  }

  def getTopNIids(uid: String, n: Int, itypes: Option[Seq[String]])(implicit app: App, algo: Algo, offlineEval: Option[OfflineEval] = None): Iterator[String] = {
    val modelset = offlineEval map { _ => false } getOrElse algo.modelset
    val itemRecScoreColl = db(collectionName(algo.id, modelset))

    itemRecScoreColl.ensureIndex(queryIndex) // not needed here. it's called in after(), just safety measure in case after() is not called

    itemRecScoreColl.findOne(MongoDBObject("uid" -> idWithAppid(app.id, uid))).map(dbObjToItemRecScore(_, app.id)).map {
      x: ItemRecScore =>

        val iids = itypes.map { s =>
          val iidsAndItypes = x.iids.zip(x.itypes.map(_.toSet)) // List( (iid, Set(itypes of this iid)), ... )
          val itypesSet: Set[String] = s.toSet // query itypes Set
          val itypesSetSize = itypesSet.size

          iidsAndItypes.filter {
            case (iid, iiditypes) =>
              // if there are some elements in s existing in iiditypes, then s.diff(iiditypes) size will be < original size of s
              // it means itypes match the item
              (itypesSet.diff(iiditypes).size < itypesSetSize)
          }.map(_._1) // only return the iid
        }.getOrElse {
          x.iids
        }

        val topNIids = if (n == 0) iids else iids.take(n)

        topNIids
    }.getOrElse(Seq()).toIterator
  }

  def getTopNIidsAndScores(uid: String, n: Int,
    itypes: Option[Seq[String]])(implicit app: App, algo: Algo,
      offlineEval: Option[OfflineEval] = None): Seq[(String, Double)] = {
    val modelset = offlineEval map { _ => false } getOrElse algo.modelset
    val itemRecScoreColl = db(collectionName(algo.id, modelset))

    itemRecScoreColl.findOne(MongoDBObject(
      "uid" -> idWithAppid(app.id, uid))).map(
      dbObjToItemRecScore(_, app.id)).map {
        x: ItemRecScore =>
          val iids = itypes.map { s =>
            val zippedIids = (x.iids, x.scores, x.itypes).zipped.toSeq
            val itypesSet: Set[String] = s.toSet // query itypes Set
            val itypesSetSize = itypesSet.size

            zippedIids.filter { z =>
              // if there are some elements in s existing in iiditypes, then
              // s.diff(iiditypes) size will be < original size of s
              // it means itypes match the item
              (itypesSet.diff(z._3.toSet).size < itypesSetSize)
            }.map(z => (z._1, z._2)) // only return the iid
          }.getOrElse {
            x.iids.zip(x.scores)
          }
          if (n == 0) iids else iids.take(n)
      } getOrElse {
        Seq[(String, Double)]()
      }
  }

  def insert(itemRecScore: ItemRecScore) = {
    val id = new ObjectId
    val itemRecObj = MongoDBObject(
      "_id" -> id,
      "uid" -> idWithAppid(itemRecScore.appid, itemRecScore.uid),
      "iids" -> itemRecScore.iids.map(i => idWithAppid(itemRecScore.appid, i)),
      "scores" -> itemRecScore.scores,
      "itypes" -> itemRecScore.itypes,
      "algoid" -> itemRecScore.algoid,
      "modelset" -> itemRecScore.modelset
    )
    db(collectionName(itemRecScore.algoid, itemRecScore.modelset))
      .insert(itemRecObj)
    itemRecScore.copy(id = Some(id))
  }

  /**
   * Insert ItemRecScore(s) and return them with real IDs.
   * This method uses the Algo ID and model set of the first
   * ItemRecScore in the sequence as the collection name.
   */
  def insert(itemRecScores: Seq[ItemRecScore]) = {
    val size = itemRecScores.size
    if (size == 0) Seq[ItemRecScore]()
    else {
      val algoid = itemRecScores(0).algoid
      val modelset = itemRecScores(0).modelset
      val ids = Seq.fill(itemRecScores.size)(new ObjectId)
      val itemRecObjsAndIds = itemRecScores.zip(ids)
      val itemRecObjs = itemRecObjsAndIds.map(t => MongoDBObject(
        "_id" -> t._2,
        "uid" -> idWithAppid(t._1.appid, t._1.uid),
        "iids" -> t._1.iids.map(i => idWithAppid(t._1.appid, i)),
        "scores" -> t._1.scores,
        "itypes" -> t._1.itypes,
        "algoid" -> t._1.algoid,
        "modelset" -> t._1.modelset))
      db(collectionName(algoid, modelset)).insert(itemRecObjs: _*)
      itemRecObjsAndIds.map(t => t._1.copy(id = Some(t._2)))
    }
  }

  def deleteByAlgoid(algoid: Int) = {
    db(collectionName(algoid, true)).drop()
    db(collectionName(algoid, false)).drop()
  }

  def deleteByAlgoidAndModelset(algoid: Int, modelset: Boolean) = {
    db(collectionName(algoid, modelset)).drop()
  }

  def existByAlgo(algo: Algo) = {
    db.collectionExists(collectionName(algo.id, algo.modelset)) && db(collectionName(algo.id, algo.modelset)).find().hasNext
  }

  override def after(algoid: Int, modelset: Boolean) = {
    val coll = db(collectionName(algoid, modelset))

    coll.ensureIndex(queryIndex)
  }

  /** Private mapping function to map DB Object to ItemRecScore object */
  private def dbObjToItemRecScore(dbObj: DBObject, appid: Int) = {
    ItemRecScore(
      uid = dbObj.as[String]("uid").drop(appid.toString.length + 1),
      iids = mongoDbListToListOfString(dbObj.as[MongoDBList]("iids")).map(_.drop(appid.toString.length + 1)),
      scores = mongoDbListToListOfDouble(dbObj.as[MongoDBList]("scores")),
      itypes = mongoDbListToListofListOfString(dbObj.as[MongoDBList]("itypes")),
      appid = appid,
      algoid = dbObj.as[Int]("algoid"),
      modelset = dbObj.as[Boolean]("modelset"),
      id = Some(dbObj.as[ObjectId]("_id"))
    )
  }

  class MongoItemRecScoreIterator(it: MongoCursor, appid: Int) extends Iterator[ItemRecScore] {
    def hasNext = it.hasNext
    def next = dbObjToItemRecScore(it.next, appid)
  }
}
