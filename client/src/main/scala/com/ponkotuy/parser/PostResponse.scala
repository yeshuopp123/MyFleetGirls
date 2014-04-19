package com.ponkotuy.parser

import scala.collection.mutable
import scala.util.Try
import java.io._
import org.json4s._
import org.json4s.native.Serialization
import org.json4s.native.Serialization.write
import com.ponkotuy.util.Log
import com.ponkotuy.data
import com.ponkotuy.data.{MapRoute, CreateShipWithId, master}
import com.ponkotuy.value.Global
import org.jboss.netty.buffer.ChannelBuffer
import com.github.theon.uri.Uri
import com.ponkotuy.tool.TempFileTool

/**
 *
 * @author ponkotuy
 * Date: 14/03/03
 */
class PostResponse extends Log {
  import com.ponkotuy.parser.ResType._
  import com.ponkotuy.http._

  implicit val formats = Serialization.formats(NoTypeHints)

  // データ転送に毎回必要
  private[this] implicit var auth: Option[data.Auth] = None
  // KDock + CreateShipのデータが欲しいのでKDockIDをKeyにCreateShipを溜めておく
  private[this] val createShips: mutable.Map[Int, data.CreateShip] = mutable.Map()
  // 現在進行中のStage情報がBattleResultで必要なので置いておく
  private[this] var mapNext: Option[data.MapStart] = None
  // 艦隊情報がRoute等で必要なので溜めておく
  private[this] var firstFleet: List[Int] = Nil

  def post(q: Query): Unit = {
    val typ = q.resType.get
    lazy val req = q.reqMap
    lazy val obj = q.resJson.get
    typ match {
      case Material =>
        val material = data.Material.fromJson(obj)
        MFGHttp.post("/material", write(material))
      case Basic =>
        auth = Some(data.Auth.fromJSON(obj))
        val basic = data.Basic.fromJSON(obj)
        MFGHttp.post("/basic", write(basic))
      case Ship3 =>
        val ship = data.Ship.fromJson(obj \ "api_ship_data")
        MFGHttp.post("/ship", write(ship))
      case NDock =>
        val docks = data.NDock.fromJson(obj)
        MFGHttp.post("/ndock", write(docks))
      case KDock =>
        val docks = data.KDock.fromJson(obj).filterNot(_.completeTime == 0)
        MFGHttp.post("/kdock", write(docks))
        docks.foreach { dock =>
          createShips.get(dock.id).foreach { cShip =>
            val dat = data.CreateShipAndDock(cShip, dock)
            MFGHttp.post("/createship", write(dat))
            createShips.remove(dock.id)
          }
        }
      case DeckPort =>
        val decks = data.DeckPort.fromJson(obj)
        firstFleet = decks.find(_.id == 1).map(_.ships).getOrElse(Nil)
        if(decks.nonEmpty) MFGHttp.post("/deckport", write(decks))
      case Deck =>
        val decks = data.DeckPort.fromJson(obj)
        firstFleet = decks.find(_.id == 1).map(_.ships).getOrElse(Nil)
        // DeckPortと同じだけど頻繁に更新する必要を感じないので送らない
        // flagshipの更新だけは建造・開発で正しいデータを送るのに必要なので更新する
      case SlotItem =>
        val items = data.SlotItem.fromJson(obj)
        MFGHttp.post("/slotitem", write(items))
      case Book2 =>
        val books = data.Book.fromJson(obj)
        if(books.isEmpty) return
        books.head match {
          case _: data.ShipBook => MFGHttp.post("/book/ship", write(books))
          case _: data.ItemBook => MFGHttp.post("/book/item", write(books))
        }
      case MapInfo =>
        val maps = data.MapInfo.fromJson(obj)
        MFGHttp.post("/mapinfo", write(maps))
      case CreateShip =>
        val createShip = data.CreateShip.fromMap(req)
        createShips(createShip.kDock) = createShip
      case GetShip =>
        val id = (obj \ "api_ship_id").extract[Int]
        createShips.remove(req("api_kdock_id").toInt).foreach { cship =>
          val withId = CreateShipWithId(cship, id)
          MFGHttp.post("/createship", write(withId), 2)
        }
      case CreateItem =>
        firstFleet.lift(0).foreach { flag =>
          val createItem = data.CreateItem.from(req, obj, flag)
          MFGHttp.post("/createitem", write(createItem))
        }
      case SortieBattleResult =>
        val result = data.BattleResult.fromJson(obj)
        MFGHttp.post("/battle_result", write((result, mapNext)))
      case MapStart =>
        val next = data.MapStart.fromJson(obj)
        mapNext = Some(next)
      case MapNext =>
        val next = data.MapStart.fromJson(obj)
        mapNext.foreach { dep =>
          val route = MapRoute.fromMapNext(dep, next, firstFleet)
          MFGHttp.post("/map_route", write(route))
        }
        mapNext = Some(next)
      case LoginCheck | Ship2 | Deck | UseItem | Practice | Record | Charge | MissionStart => // No Need
      case HenseiChange | HenseiLock | GetOthersDeck => // No Need
      case MasterUseItem | MasterFurniture => // No Need
      case MasterShip =>
        if(checkPonkotu) {
          val ships = master.MasterShip.fromJson(obj)
          MFGHttp.post("/master/ship", write(ships))
        }
      case MasterMission =>
        if(checkPonkotu) {
          val missions = master.MasterMission.fromJson(obj)
          MFGHttp.post("/master/mission", write(missions))
        }
      case MasterSlotItem =>
        if(checkPonkotu) {
          val items = master.MasterSlotItem.fromJson(obj)
          MFGHttp.post("/master/slotitem", write(items))
        }
      case MasterSType =>
        if(checkPonkotu) {
          val stype = master.MasterSType.fromJson(obj)
          MFGHttp.post("/master/stype", write(stype))
        }
      case ShipSWF =>
        parseId(q.uri).filterNot(MFGHttp.existsImage).foreach { id =>
          val swf = allRead(q.res.getContent)
          val file = TempFileTool.save(swf, "swf")
          MFGHttp.postFile("/swf/ship/" + id, "image")(file)
        }
      case SoundMP3 =>
        SoundUrlId.parseURL(q.uri).filterNot(MFGHttp.existsSound).foreach { case SoundUrlId(shipId, soundId) =>
          val sound = allRead(q.res.getContent)
          val file = TempFileTool.save(sound, "mp3")
          MFGHttp.postFile(s"/mp3/kc/${shipId}/${soundId}", "sound")(file)
        }
      case _ =>
        info(s"ResType: $typ")
        info(s"Req: $req")
        jsonInfo(obj)
    }
  }

  private def checkPonkotu: Boolean = auth.exists(u => Global.Admin.contains(u.memberId))

  private def parseId(str: String): Option[Int] =
    Try {
      val uri = Uri.parseUri(str)
      val filename = uri.pathParts.last
      filename.takeWhile(_ != '.').toInt
    }.toOption

  private def allRead(cb: ChannelBuffer): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    cb.getBytes(0, baos, cb.readableBytes())
    baos.toByteArray
  }
}

case class SoundUrlId(shipId: Int, soundId: Int)

object SoundUrlId {
  val pattern = """.*/kcs/sound/kc(\d+)/(\d+).mp3""".r

  def parseURL(url: String): Option[SoundUrlId] = {
    url match {
      case pattern(ship, sound) => Try { SoundUrlId(ship.toInt, sound.toInt) }.toOption
      case _ => None
    }
  }
}
