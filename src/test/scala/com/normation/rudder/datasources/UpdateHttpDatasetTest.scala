/*
*************************************************************************************
* Copyright 2016 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.datasources

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

import com.normation.BoxSpecMatcher
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.datasources.DataSourceSchedule._
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.InterpolatedValueCompilerImpl
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.utils.StringUuidGeneratorImpl

import org.http4s._
import org.http4s.dsl._
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.util._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.specification.AfterAll
import org.specs2.runner.JUnitRunner

import monix.execution.atomic.AtomicInt
import monix.execution.schedulers.TestScheduler
import net.liftweb.common._



@RunWith(classOf[JUnitRunner])
class UpdateHttpDatasetTest extends Specification with BoxSpecMatcher with Loggable with AfterAll {

  //utility to compact render a json string
  //will throws exceptions if errors
  def compact(json: String): String = {
    import net.liftweb.json._
    compactRender(parse(json))
  }

  //create a rest server for test
  object NodeDataset {

    //for debuging - of course works correctly only if sequential
    val counterError   = AtomicInt(0)
    val counterSuccess = AtomicInt(0)

    def reset(): Unit = {
      counterError.set(0)
      counterSuccess.set(0)
    }

    def service(implicit executionContext: ExecutionContext = ExecutionContext.global) = HttpService {
      case _ -> Root =>
        MethodNotAllowed()

      case GET -> Root / "single_node1" =>
        Ok{
          counterSuccess.add(1)
          booksJson
        }

      case GET -> Root / x =>
        Ok {
          counterSuccess.add(1)
          nodeJson(x)
        }

      case r @ GET -> Root / "delay" / x =>
        r.headers.get(CaseInsensitiveString("nodeId")).map( _.value) match {
          case Some(`x`) =>
            Ok {
              counterSuccess.add(1)
              nodeJson(x)
            }.after(Random.nextInt(1000).millis)

          case _ =>
            Forbidden {
              counterError.add(1)
              "node id was not found in the 'nodeid' header"
            }
        }

      case r @ POST -> Root / "delay" =>

        val headerId = r.headers.get(CaseInsensitiveString("nodeId")).map( _.value)

        r.decode[UrlForm] { data =>
          val formId = data.values.get("nodeId").flatMap(_.headOption)

          (headerId, formId) match {
            case (Some(x), Some(y)) if x == y =>
              Ok {
                counterSuccess.add(1)
                nodeJson("plop")
              }.after(Random.nextInt(1000).millis)

            case _ =>
              Forbidden {
                counterError.add(1)
                "node id was not found in post form (key=nodeId)"
              }
          }
        }

      case GET -> Root / "faileven" / x =>
        // x === "nodeXX" or root
        if(x != "root" && x.replaceAll("node", "").toInt % 2 == 0) {
          Forbidden {
            counterError.add(1)
            "Not authorized"
          }
        } else {
          Ok {
            counterSuccess.add(1)
            nodeJson(x)
          }
        }
    }
  }
  //start server
  val server = BlazeBuilder.bindHttp(8282)
    .mountService(NodeDataset.service, "/datasource")
    .run

  override def afterAll(): Unit = {
    server.shutdown
  }

  val actor = EventActor("Test-actor")
  def modId = ModificationId("test-id-@" + System.currentTimeMillis)

  val interpolation = new InterpolatedValueCompilerImpl
  val fetch = new GetDataset(interpolation)

  val parameterRepo = new RoParameterRepository() {
    def getAllGlobalParameters() = Full(Seq())
    def getAllOverridable() = Full(Seq())
    def getGlobalParameter(parameterName: ParameterName) = Empty
  }

  class TestNodeRepoInfo(initNodeInfo: Map[NodeId, NodeInfo]) extends WoNodeRepository with NodeInfoService {

    private[this] var nodes = initNodeInfo

    //used for test
    //number of time each node is updated
    val updates = scala.collection.mutable.Map[NodeId, Int]()

    // WoNodeRepository methods
    def updateNode(node: Node, modId: ModificationId, actor: EventActor, reason: Option[String]): Box[Node] = this.synchronized {
      for {
        existing <- Box(nodes.get(node.id)) ?~! s"Missing node with key ${node.id.value}"
      } yield {
        this.updates += (node.id -> (1 + updates.getOrElse(node.id, 0) ) )
        this.nodes = (nodes + (node.id -> existing.copy(node = node) ) )
        node
      }
    }

    // NodeInfoService
    def getAll() = synchronized(Full(nodes))
    def getAllNodes()                         = throw new IllegalAccessException("Thou shall not used that method here")
    def getAllSystemNodeIds()                 = throw new IllegalAccessException("Thou shall not used that method here")
    def getDeletedNodeInfo(nodeId: NodeId)    = throw new IllegalAccessException("Thou shall not used that method here")
    def getDeletedNodeInfos()                 = throw new IllegalAccessException("Thou shall not used that method here")
    def getLDAPNodeInfo(nodeIds: Set[NodeId]) = throw new IllegalAccessException("Thou shall not used that method here")
    def getNode(nodeId: NodeId)               = throw new IllegalAccessException("Thou shall not used that method here")
    def getNodeInfo(nodeId: NodeId)           = throw new IllegalAccessException("Thou shall not used that method here")
    def getPendingNodeInfo(nodeId: NodeId)    = throw new IllegalAccessException("Thou shall not used that method here")
    def getPendingNodeInfos()                 = throw new IllegalAccessException("Thou shall not used that method here")
  }

  val root = NodeConfigData.root
  val n1 = {
    val n = NodeConfigData.node1.node
    NodeConfigData.node1.copy(node = n.copy(properties = DataSource.nodeProperty("get-that", "book") :: Nil ))
  }

  val httpDatasourceTemplate = DataSourceType.HTTP(
      "CHANGE MY URL"
    , Map()
    , HttpMethod.GET
    , Map()
    , true
    , "CHANGE MY PATH"
    , HttpRequestMode.OneRequestByNode
    , 30.second
  )
  val datasourceTemplate = DataSource(
        DataSourceId("test-my-datasource")
      , DataSourceName("test-my-datasource")
      , httpDatasourceTemplate
      , DataSourceRunParameters(
            Scheduled(300.seconds)
          , true
          , true
        )
      , "a test datasource to test datasources"
      , true
      , 5.minutes
    )
  // create a copy of template, updating some properties
  def NewDataSource(
      name   : String
    , url    : String = httpDatasourceTemplate.url
    , path   : String = httpDatasourceTemplate.path
    , schedule: DataSourceSchedule = datasourceTemplate.runParam.schedule
    , method: HttpMethod = httpDatasourceTemplate.httpMethod
    , params: Map[String, String] = httpDatasourceTemplate.params
    , headers: Map[String, String] = httpDatasourceTemplate.headers
  ) = {
    val http = httpDatasourceTemplate.copy(url = url, path = path, httpMethod = method, params = params, headers = headers)
    val run  = datasourceTemplate.runParam.copy(schedule = schedule)
    datasourceTemplate.copy(name = DataSourceName(name), sourceType = http, runParam = run)

  }

  object MyDatasource {

    val infos = new TestNodeRepoInfo(NodeConfigData.allNodesInfo)
    val http = new HttpQueryDataSourceService(
        infos
      , parameterRepo
      , infos
      , interpolation
    )


    val uuidGen = new StringUuidGeneratorImpl()


  }


  sequential

  "Update on datasource" should {
    val datasource = NewDataSource(
        name = "test-scheduler"
      , url  = "http://localhost:8282/datasource/${rudder.node.id}"
      , path = "$.hostname"
      , schedule = Scheduled(5.minute)
    )
    val action = (c: UpdateCause) => {
      // here we need to give him the default scheduler, not the test one,
      // to actually have the fetch logic done
      MyDatasource.http.queryAll(datasource, c) match {
        case Full(res) => //ok
        case x         => logger.error(s"oh no! Got a $x")
      }
    }

    "does nothing is disabled scheduler" in {
      val testScheduler = TestScheduler()
      val dss = new DataSourceScheduler(
          datasource.copy(enabled = false)
        , testScheduler
        , () => ModificationId(MyDatasource.uuidGen.newUuid)
        , action
     )

      //reset counter
      NodeDataset.reset()
      // before start, nothing is done
      val total_0 = NodeDataset.counterError.get + NodeDataset.counterSuccess.get
      dss.start()
      //then, event after days, nothing is done
      testScheduler.tick(1.day)
      val total_1d = NodeDataset.counterError.get + NodeDataset.counterSuccess.get

      (total_0, total_1d) must beEqualTo(
      (0      , 0       ))
    }

    "allows interactive updates with disabled scheduler (but not data source)" in {
      val testScheduler = TestScheduler()
      val dss = new DataSourceScheduler(
          datasource.copy(runParam = datasource.runParam.copy(schedule = NoSchedule(1.second)))
        , testScheduler
        , () => ModificationId(MyDatasource.uuidGen.newUuid)
        , action
     )

      //reset counter
      NodeDataset.reset()
      // before start, nothing is done
      val total_0 = NodeDataset.counterError.get + NodeDataset.counterSuccess.get
      dss.start()
      //then, event after days, nothing is done
      testScheduler.tick(1.day)
      val total_1d = NodeDataset.counterError.get + NodeDataset.counterSuccess.get
      //but asking for a direct update do the queries immediatly - task need at least 1ms to notice it should run
      dss.doActionAndSchedule(action(UpdateCause(ModificationId("plop"), RudderEventActor, None)))
      testScheduler.tick(1.millis)
      val total_postGen = NodeDataset.counterError.get + NodeDataset.counterSuccess.get

      (total_0, total_1d, total_postGen                   ) must beEqualTo(
      (0      , 0       , NodeConfigData.allNodesInfo.size))

    }

    "create a new schedule from data source information" in {
      val testScheduler = TestScheduler()
      val dss = new DataSourceScheduler(
          datasource
        , testScheduler
        , () => ModificationId(MyDatasource.uuidGen.newUuid)
        , action
     )

      //reset counter
      NodeDataset.reset()

      // before start, nothing is done
      val total_0 = NodeDataset.counterError.get + NodeDataset.counterSuccess.get
      dss.start()
      //then just after, we have the first exec - it still need at least a ms to tick
      //still nothing here
      val total_0plus = NodeDataset.counterError.get + NodeDataset.counterSuccess.get
      testScheduler.tick(1.millis)
      //here we have results
      val total_1s = NodeDataset.counterError.get + NodeDataset.counterSuccess.get
      //then nothing happens before 5 minutes
      testScheduler.tick(4.minutes)
      val total_4min = NodeDataset.counterError.get + NodeDataset.counterSuccess.get
      //then all the nodes gets their info
      testScheduler.tick(1.minute)
      val total_5min = NodeDataset.counterError.get + NodeDataset.counterSuccess.get

      //then nothing happen anymore
      testScheduler.tick(3.minutes)
      val total_8min = NodeDataset.counterError.get + NodeDataset.counterSuccess.get

      val size = NodeConfigData.allNodesInfo.size
      (total_0, total_0plus, total_1s, total_4min, total_5min, total_8min) must beEqualTo(
      (0      , 0          , size    , size      ,  size*2,    size*2    ))
    }

  }

  "querying a lot of nodes" should {

    val nodes = (NodeConfigData.root :: List.fill(1000)(NodeConfigData.node1).zipWithIndex.map { case (n,i) =>
      val name = "node"+i
      n.copy(node = n.node.copy(id = NodeId(name), name = name), hostname = name+".localhost")
    }).map( n => (n.id, n)).toMap
    val infos = new TestNodeRepoInfo(nodes)
    val http = new HttpQueryDataSourceService(
        infos
      , parameterRepo
      , infos
      , interpolation
    )


    "work even if nodes don't reply at same speed with GET" in {
      val ds = NewDataSource(
          "test-lot-of-nodes-GET"
        , url  = "http://localhost:8282/datasource/delay/${rudder.node.id}"
        , path = "$.hostname"
        , headers = Map( "nodeId" -> "${rudder.node.id}" )
      )
      val nodeIds = infos.getAll().openOrThrowException("test shall not throw").keySet
      //all node updated one time
      infos.updates.clear()
      NodeDataset.reset()
      val t0 = System.currentTimeMillis
      val res = http.queryAll(ds, UpdateCause(modId, actor, None))
      val t1 = System.currentTimeMillis

      res mustFullEq(nodeIds) and (
        infos.updates.toMap must havePairs( nodeIds.map(x => (x, 1) ).toSeq:_* )
      ) and (NodeDataset.counterError.get === 0) and (NodeDataset.counterSuccess.get === nodeIds.size)
    }

    "work even if nodes don't reply at same speed with POST" in {
      val ds = NewDataSource(
          "test-lot-of-nodes-POST"
        , url  = "http://localhost:8282/datasource/delay"
        , path = "$.hostname"
        , method = HttpMethod.POST
        , params = Map( "nodeId" -> "${rudder.node.id}" )
        , headers = Map( "nodeId" -> "${rudder.node.id}" )
      )
      val nodeIds = infos.getAll().openOrThrowException("test shall not throw").keySet
      //all node updated one time
      infos.updates.clear()
      NodeDataset.reset()
      val t0 = System.currentTimeMillis
      val res = http.queryAll(ds, UpdateCause(modId, actor, None))
      val t1 = System.currentTimeMillis

      println(infos.updates.toList.sortBy(_._1.value))

      res mustFullEq(nodeIds) and (
        infos.updates.toMap must havePairs( nodeIds.map(x => (x, 1) ).toSeq:_* )
      ) and (NodeDataset.counterError.get === 0) and (NodeDataset.counterSuccess.get === nodeIds.size)
    }

    "work for odd node even if even nodes fail" in {
      val ds = NewDataSource(
          "test-even-fail"
        , url  = "http://localhost:8282/datasource/faileven/${rudder.node.id}"
        , path = "$.hostname"
      )
      val nodeRegEx = "node(.*)".r
      val nodeIds = infos.getAll().openOrThrowException("test shall not throw").keySet.filter(n => n.value match {
        case "root"       => true
        case nodeRegEx(i) => i.toInt % 2 == 1
      })
      //all node updated one time
      infos.updates.clear()

      val res = http.queryAll(ds, UpdateCause(modId, actor, None))
      res mustFails() and (
        infos.updates.toMap must havePairs( nodeIds.map(x => (x, 1) ).toSeq:_* )
      )

    }
  }


  "Getting a node" should {
    val datasource = httpDatasourceTemplate.copy(
        url  = "http://localhost:8282/datasource/single_${rudder.node.id}"
      , path = "$.store.${node.properties[get-that]}"
    )
    "get the node" in  {

      val res = fetch.getNode(DataSourceId("test-get-one-node"), datasource, n1, root, Set(), 1.second, 5.seconds)

      res mustFullEq(
          DataSource.nodeProperty("test-get-one-node", compact("""{
            "category": "reference",
            "author": "Nigel Rees",
            "title": "Sayings of the Century",
            "price": 8.95
          }""")))

    }
  }

  "The full http service" should {
    val datasource = NewDataSource(
        "test-http-service"
      , url  = "http://localhost:8282/datasource/single_node1"
      , path = "$.store.book"
    )

    val infos = new TestNodeRepoInfo(NodeConfigData.allNodesInfo)
    val http = new HttpQueryDataSourceService(
        infos
      , parameterRepo
      , infos
      , interpolation
    )

    "correctly update all nodes" in {
      //all node updated one time
      val nodeIds = infos.getAll().openOrThrowException("test shall not throw").keySet
      infos.updates.clear()
      val res = http.queryAll(datasource, UpdateCause(modId, actor, None))

      res mustFullEq(nodeIds) and (
        infos.updates.toMap must havePairs( nodeIds.map(x => (x, 1) ).toSeq:_* )
      )
    }
  }


  lazy val booksJson = """
  {
    "store": {
        "book": [
            {
                "category": "reference",
                "author": "Nigel Rees",
                "title": "Sayings of the Century",
                "price": 8.95
            },
            {
                "category": "fiction",
                "author": "Evelyn Waugh",
                "title": "Sword of Honour",
                "price": 12.99
            },
            {
                "category": "fiction",
                "author": "Herman Melville",
                "title": "Moby Dick",
                "isbn": "0-553-21311-3",
                "price": 8.99
            },
            {
                "category": "fiction",
                "author": "J. R. R. Tolkien",
                "title": "The Lord of the Rings",
                "isbn": "0-395-19395-8",
                "price": 22.99
            }
        ],
        "bicycle": {
            "color": "red",
            "price": 19.95
        }
    },
    "expensive": 10
  }
  """

  //expample of what a CMDB could return for a node.
  def nodeJson(name: String) = s""" {
    "hostname" : "$name",
    "ad_groups" : [ "ad-grp1 " ],
    "ssh_groups" : [ "ssh-power-users" ],
    "sudo_groups" : [ "sudo-masters" ],
    "hostnames" : {
     "fqdn" : "$name.some.host.com $name",
     "local" : "localhost.localdomain localhost localhost4 localhost4.localdomain4"
    },
    "netfilter4_rules" : {
     "all" : "lo",
     "ping" : "eth0",
     "tcpint" : "",
     "udpint" : "",
     "exceptions" : "",
     "logdrop" : false,
     "gateway" : false,
     "extif" : "eth0",
     "intif" : "eth1"
    },
  "netfilter6_rules" : {
     "all" : "lo",
     "ping" : "eth0",
     "tcpint" : "",
     "udpint" : "",
     "exceptions" : "",
     "logdrop" : false,
     "gateway" : false,
     "extif" : "eth0",
     "intif" : "eth1"
    }
  }
  """

}
