package com.indoors.verticle

/**
 * @author wupanjie on 2017/11/27.
 */
import com.indoors.*
import com.indoors.common.Result
import com.indoors.common.computePosition
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.mongo.MongoClient
import io.vertx.ext.mongo.MongoClientUpdateResult
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.core.json.JsonArray
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.mongo.FindOptions
import jkid.deserialization.deserialize
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import kotlin.coroutines.experimental.suspendCoroutine

class RESTVerticle : AbstractVerticle() {

  private val logger = LoggerFactory.getLogger(this::class.java)
  private lateinit var mongoClient: MongoClient

  override fun start() {
    // config sql client
    mongoClient = MongoClient.createShared(vertx, mongoConfig)

    // config router
    val router = Router.router(vertx)

    router.route().handler(BodyHandler.create())

    router.post("/wifi/upload").handler { uploadWifiData(it) }
    router.get("/room/info").handler { fetchRoomInfo(it) }
    router.get("/room/list").handler { fetchRoomList(it) }
    router.post("/room/location").handler { fetchRoomPosition(it) }
    router.post("/room/new").handler { createNewRoom(it) }
    router.post("/room/delete").handler { deleteRoom(it) }
    router.post("/room/positions/clear").handler { clearRoomPosition(it) }

    // create server
    vertx.createHttpServer().requestHandler({ router.accept(it) }).listen(8080)
  }

  private fun clearRoomPosition(routingContext: RoutingContext) {
    val request = routingContext.request()
    val response = routingContext.response()
    val roomId = request.getParam("room_id")

    if (roomId.isNullOrEmpty()) {
      response.failWith("need param room_id")
      return
    }

    vertxCoroutine {
      val result = resultWith<MongoClientUpdateResult> { handler ->
        val query = json {
          obj(
              "_id" to roomId
          )
        }

        val update = json {
          obj(
              "\$set" to obj(
                  "positions" to JsonArray()
              )
          )
        }

        mongoClient.updateCollection("room", query, update, handler)
      }

      result.fold(success = { mongoClientUpdateResult ->
        var message = "success"
        if (mongoClientUpdateResult.docMatched == 0L) {
          message = "No room found with $roomId"
        }
        response.successWith(message = message)
      }, failure = { response.failWith(it) })
    }
  }

  

  private fun deleteRoom(routingContext: RoutingContext) {
    val request = routingContext.request()
    val response = routingContext.response()
    val roomId = request.getParam("room_id")

    if (roomId.isNullOrEmpty()) {
      response.failWith("need param room_id")
      return
    }

    vertxCoroutine {
      val result = resultWith<JsonObject> { handler ->
        val query = json {
          obj("_id" to roomId)
        }
        mongoClient.findOneAndDelete("room", query, handler)
      }

      result.fold(success = { response.successWith() }, failure = { response.failWith(it) })
    }
  }

  // TODO 关于同名
  private fun createNewRoom(routingContext: RoutingContext) {
    val request = routingContext.request()
    val response = routingContext.response()

    val name = request.getParam("name")
    if (name.isNullOrEmpty()) {
      response.failWith("need param name")
      return
    }

    vertxCoroutine {
      val result = resultWith<String> { handler ->
        val document = json {
          obj("room_name" to name)
        }
        mongoClient.insert("room", document, handler)
      }

      result.fold(success = { id ->
        val data = json {
          obj("room_id" to id)
        }
        response.successWith(data = data)
      }, failure = { response.failWith(it) })
    }
  }

  private fun fetchRoomPosition(routingContext: RoutingContext) {
    val request = routingContext.request()
    val response = routingContext.response()
    val roomId = request.getParam("room_id")

    if (roomId.isNullOrEmpty()) {
      response.failWith("need param room_id")
      return
    }

    vertxCoroutine {
      val result: Result<Position, Exception> = computePosition(roomId, routingContext)
      result.fold(success = { position ->
        logger.debug(position)

        val data = json {
          obj(
              "x" to position.x,
              "y" to position.y
          )
        }

        response.successWith(data = data)
      }, failure = { response.failWith(it) })
    }

  }

  private suspend fun computePosition(roomId: String, routingContext: RoutingContext): Result<Position, Exception> {
    findRoom(roomId).await()
        .fold(success = { roomInfo ->

          val needCompute = deserialize<RoomPosition>(routingContext.bodyAsString)
          val room = deserialize<Room>(roomInfo.toString())

          return Result.of { room.computePosition(needCompute.wifi_stats) }
        }, failure = { error ->
          return Result.error(error)
        })
  }

  private suspend fun findRoom(roomId: String) = async {
    val query = json {
      obj("_id" to roomId)
    }

    val result = resultWith<JsonObject> { handler ->
      mongoClient.findOne("room", query, null, handler)
    }

    return@async result
  }

  private fun fetchRoomInfo(routingContext: RoutingContext) {
    val request = routingContext.request()
    val response = routingContext.response()
    val roomId = request.getParam("room_id")

    if (roomId.isNullOrEmpty()) {
      response.failWith("need param room_id")
      return
    }

    vertxCoroutine {

      findRoom(roomId).await()
          .fold(success = { room ->
            val data = json {
              obj("room" to room)
            }
            response.successWith(data = data)
          }, failure = { response.failWith(it) })

    }
  }

  private fun uploadWifiData(routingContext: RoutingContext) {
    val request = routingContext.request()
    val response = routingContext.response()
    val roomId = request.getParam("room_id")
    if (roomId.isNullOrEmpty()) {
      response.failWith("need param room_id")
      return
    }

    vertxCoroutine {
      updateOrPushPositions(roomId, routingContext.bodyAsJson)
    }.invokeOnCompletion { cause ->
      if (cause == null) {
        response.successWith()
      } else {
        response.failWith(cause = cause)
      }
    }

  }

  private suspend fun updateOrPushPositions(roomId: String, roomPosition: JsonObject):
      Boolean = suspendCoroutine { cont ->
    launch(vertx.dispatcher()) {
      val wifi_stats = roomPosition.getJsonArray("wifi_stats")

      val x = roomPosition.getDouble("x", 0.0)
      val y = roomPosition.getDouble("y", 0.0)

      val querySet = json {
        obj(
            "_id" to roomId,
            "positions" to obj(
                "\$elemMatch" to obj(
                    "x" to x,
                    "y" to y
                )
            )
        )
      }

      logger.debug(querySet)

      val updateSet = json {
        obj(
            "\$set" to obj(
                "positions.$.wifi_stats" to wifi_stats
            )
        )
      }

      resultWith<MongoClientUpdateResult> { handler ->
        mongoClient.updateCollection("room", querySet, updateSet, handler)
      }.fold(
          success = { mongoClientUpdateResult ->
            logger.debug(mongoClientUpdateResult.toJson())

            if (mongoClientUpdateResult.docMatched > 0L) {
              cont.resume(true)
              return@launch
            }

            logger.debug("push item")
            // if not update, push it
            val queryPush = json {
              obj("_id" to roomId)
            }

            val updatePush = json {
              obj(
                  "\$push" to obj(
                      "positions" to roomPosition
                  )
              )
            }

            resultWith<MongoClientUpdateResult> { handler ->
              mongoClient.updateCollection("room", queryPush, updatePush, handler)
            }.fold(
                success = { cont.resume(false) },
                failure = cont::resumeWithException
            )
          },
          failure = cont::resumeWithException
      )
    }

  }

  private fun fetchRoomList(routingContext: RoutingContext) {
    val request = routingContext.request()
    val response = routingContext.response()

    vertxCoroutine {
      val result = resultWith<List<JsonObject>> { handler ->
        var limit = request.getParamOrElse("limit", "10").toInt()
        val offset = request.getParamOrElse("offset", "0").toInt()

        // avoid query all data
        if (limit <= 0) limit = 10

        val options = FindOptions(limit = limit, skip = offset)
        mongoClient.findWithOptions("room", JsonObject(), options, handler)
      }

      result.fold(success = { list ->

        val data = json {
          obj("rooms" to list)
        }

        response.successWith(data = data)
      }, failure = { response.failWith(it) })

    }
  }
}