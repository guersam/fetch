import scala.collection.immutable.Seq
import scala.collection.immutable.Queue

import cats.{ Cartesian, Monad, MonadError, ~> }
import cats.data.{ StateT, Const }
import cats.std.option._
import cats.std.list._
import cats.syntax.traverse._
import cats.free.{ Free }

package object fetch {
  /**
    * A `DataSource` is the recipe for fetching a certain identity `I`, which yields
    * results of type `A` with the concurrency and error handling specified by the Monad
    * `M`.
    */
  trait DataSource[I, A, M[_]] {
    def name: DataSourceName = this.toString
    def identity(i: I): DataSourceIdentity = (name, i)
    def fetch(ids: List[I]): M[Map[I, A]]
  }

  /**
    * A marker trait for cache implementations.
    */
  trait DataSourceCache

  /**
    * A `Cache` trait so the users of the library can provide their own cache.
    */
  trait Cache[T <: DataSourceCache]{
    def update[I, A](c: T, k: DataSourceIdentity, v: A): T

    def get[I](c: T, k: DataSourceIdentity): Option[Any]

    def cacheResults[I, A, M[_]](cache: T, results: Map[I, A], ds: DataSource[I, A, M]): T = {
      results.foldLeft(cache)({
        case (acc, (i, a)) => update(acc, ds.identity(i), a)
      })
    }
  }

  /**
    * An environment that is passed along during the fetch rounds. It holds the
    * cache and the list of rounds that have been executed.
    */
  trait Env[C <: DataSourceCache]{
    def cache: C
    def rounds: Seq[Round]

    def cached: Seq[Round] =
      rounds.filter(_.cached)

    def uncached: Seq[Round] =
      rounds.filterNot(_.cached)

    def next(
      newCache: C,
      newRound: Round,
      newIds: List[Any]
    ): Env[C]
  }

  /**
    * A data structure that holds information about a fetch round.
    */
  case class Round(
    cache: DataSourceCache,
    ds: DataSourceName,
    kind: RoundKind,
    startRound: Long,
    endRound: Long,
    cached: Boolean = false
  ) {
    def duration: Double = (endRound - startRound) / 1e6

    def isConcurrent: Boolean = kind match {
      case ConcurrentRound(_) => true
      case _ => false
    }
  }

  sealed trait RoundKind
  final case class OneRound(id: Any) extends RoundKind
  final case class ManyRound(ids: List[Any]) extends RoundKind
  final case class ConcurrentRound(ids: Map[String, List[Any]]) extends RoundKind

  /**
    * A concrete implementation of `Env` used in the default Fetch interpreter.
    */
  case class FetchEnv[C <: DataSourceCache](
    cache: C,
    ids: List[Any] = Nil,
    rounds: Queue[Round] = Queue.empty
  ) extends Env[C]{
    def next(
      newCache: C,
      newRound: Round,
      newIds: List[Any]
    ): FetchEnv[C] =
      copy(cache = newCache, rounds = rounds :+ newRound, ids = newIds)
  }

  /**
    * An exception thrown from the interpreter when failing to perform a data fetch.
    */
  case class FetchFailure[C <: DataSourceCache](env: Env[C])(
    implicit CC: Cache[C]
  ) extends Throwable

  // Algebra

  /**
    * Primitive operations in the Fetch Free monad.
    */
  sealed abstract class FetchOp[A] extends Product with Serializable

  final case class Cached[A](a: A) extends FetchOp[A]
  final case class FetchOne[I, A, M[_]](a: I, ds: DataSource[I, A, M]) extends FetchOp[A]
  final case class FetchMany[I, A, M[_]](as: List[I], ds: DataSource[I, A, M]) extends FetchOp[List[A]]
  final case class Concurrent[C <: DataSourceCache, E <: Env[C], M[_]](as: List[FetchMany[_, _, M]]) extends FetchOp[E]
  final case class FetchError[A, E <: Throwable](err: E) extends FetchOp[A]

  // Types

  type DataSourceName = String

  type DataSourceIdentity = (DataSourceName, Any)

  type Fetch[A] = Free[FetchOp, A]

  type FetchInterpreter[M[_], C <: DataSourceCache] = {
    type f[x] = StateT[M, FetchEnv[C], x]
  }

  // Cache

  /** A cache that stores its elements in memory.
    */
  case class InMemoryCache(state:Map[Any, Any]) extends DataSourceCache

  object InMemoryCache {
    def empty: InMemoryCache = InMemoryCache(Map.empty[Any, Any])

    def apply(results: (Any, Any)*): InMemoryCache =
      InMemoryCache(results.foldLeft(Map.empty[Any, Any])({
        case (c, (k, v)) => c.updated(k, v)
      }))
  }

  implicit object InMemoryCacheImpl extends Cache[InMemoryCache]{
    override def get[I](c: InMemoryCache, k: DataSourceIdentity): Option[Any] = c.state.get(k)
    override def update[I, A](c: InMemoryCache, k: DataSourceIdentity, v: A): InMemoryCache = InMemoryCache(c.state.updated(k, v))
  }

  object Fetch {
    /**
      * Lift a plain value to the Fetch monad.
      */
    def pure[A](a: A): Fetch[A] =
      Free.pure(a)

    /**
      * Lift an error to the Fetch monad.
      */
    def error[A](e: Throwable): Fetch[A] =
      Free.liftF(FetchError(e))

    /**
      * Given a value that has a related `DataSource` implementation, lift it
      * to the `Fetch` monad. When executing the fetch the data source will be
      * queried and the fetch will return its result.
      */
    def apply[I, A, M[_]](i: I)(
      implicit DS: DataSource[I, A, M]
    ): Fetch[A] =
      Free.liftF(FetchOne[I, A, M](i, DS))

    def deps[A, M[_]](f: Fetch[_]): List[FetchOp[_]] = {
      type FM = List[FetchOp[_]]

      f.foldMap[Const[FM, ?]](new (FetchOp ~> Const[FM, ?]) {
        def apply[X](x: FetchOp[X]): Const[FM, X] = x match {
          case one@FetchOne(id, ds) => Const(List(FetchMany(List(id), ds.asInstanceOf[DataSource[Any, A, M]])))
          case conc@Concurrent(as) => Const(as.asInstanceOf[FM])
          case cach@Cached(a) => Const(List(cach))
          case _ => Const(List())
        }
      })(new Monad[Const[FM, ?]] {
        def pure[A](x: A): Const[FM, A] = Const(List())

        def flatMap[A, B](fa: Const[FM, A])(f: A => Const[FM, B]): Const[FM, B] = fa match {
          case Const(List(Cached(a))) => f(a.asInstanceOf[A])
          case other => fa.asInstanceOf[Const[FM, B]]
        }

      }).getConst
    }

    def combineDeps[M[_]](ds: List[FetchOp[_]]): List[FetchMany[_, _, M]] = {
      ds.foldLeft(Map.empty[Any, List[_]])((acc, op) => op match {
        case one@FetchOne(id, ds) => acc.updated(ds, acc.get(ds).fold(List(id))(accids => accids :+ id))
        case many@FetchMany(ids, ds) => acc.updated(ds, acc.get(ds).fold(ids)(accids => accids ++ ids))
        case _ => acc
      }).toList.map({
        case (ds, ids) => FetchMany[Any, Any, M](ids, ds.asInstanceOf[DataSource[Any, Any, M]])
      })
    }

    private[this] def concurrently[C <: DataSourceCache, E <: Env[C], M[_]](fa: Fetch[_], fb: Fetch[_]): Fetch[E] = {
      val fetches: List[FetchMany[_, _, M]] = combineDeps(deps(fa) ++ deps(fb))
      Free.liftF(Concurrent[C, E, M](fetches))
    }

    /**
      * Collect a list of fetches into a fetch of a list. It implies concurrent execution of fetches.
      */
    def collect[I, A](ids: List[Fetch[A]]): Fetch[List[A]] = {
      ids.foldLeft(Fetch.pure(List(): List[A]))((f, newF) =>
        Fetch.join(f, newF).map(t => t._1 :+ t._2)
      )
    }

    /**
      * Apply a fetch-returning function to every element in a list and return a Fetch of the list of
      * results. It implies concurrent execution of fetches.
      */
    def traverse[A, B](ids: List[A])(f: A => Fetch[B]): Fetch[List[B]] =
      collect(ids.map(f))

    /**
      * Apply the given function to the result of the two fetches. It implies concurrent execution of fetches.
      */
    def map2[A, B, C](f: (A, B) => C)(fa: Fetch[A], fb: Fetch[B]): Fetch[C] =
      Fetch.join(fa, fb).map({ case (a, b) => f(a, b) })

    /**
      * Join two fetches from any data sources and return a Fetch that returns a tuple with the two
      * results. It implies concurrent execution of fetches.
      */
    def join[A, B, C <: DataSourceCache, E <: Env[C], M[_]](fl: Fetch[A], fr: Fetch[B])(
      implicit
        CC: Cache[C]
    ): Fetch[(A, B)] = {
      for {
        env <- concurrently[C, E, M](fl, fr)

        result <- {

          val simplify: FetchOp ~> FetchOp = new (FetchOp ~> FetchOp) {
            def apply[B](f: FetchOp[B]): FetchOp[B] = f match {
              case one@FetchOne(id, ds) => {
                CC.get(env.cache, ds.identity(id)).fold(one : FetchOp[B])(b => Cached(b).asInstanceOf[FetchOp[B]])
              }
              case many@FetchMany(ids, ds) => {
                val results = ids.flatMap(id =>
                  CC.get(env.cache, ds.identity(id))
                )

                if (results.size == ids.size) {
                  Cached(results)
                } else {
                  many
                }
              }
              case conc@Concurrent(manies) => {
                val newManies = manies.filterNot({fm =>
                  val ids: List[Any] = fm.as
                  val ds: DataSource[Any, _, M] = fm.ds.asInstanceOf[DataSource[Any, _, M]]

                  val results = ids.flatMap(id => {
                    CC.get(env.cache, ds.identity(id))
                  })

                  results.size == ids.size
                }).asInstanceOf[List[FetchMany[_, _, M]]]

                if (newManies.isEmpty)
                  Cached(env).asInstanceOf[FetchOp[B]]
                else
                  Concurrent(newManies).asInstanceOf[FetchOp[B]]
              }
              case other => other
            }
          }

          val sfl = fl.compile(simplify)
          val sfr = fr.compile(simplify)
          val remainingDeps = combineDeps(deps(sfl) ++ deps(sfr))

          if (remainingDeps.isEmpty) {
            for {
              a <- sfl
              b <- sfr
            } yield (a, b)
          } else {
            join[A, B, C, E, M](sfl, sfr)
          }
        }
      } yield result
    }

    /**
      * Run a `Fetch` with the given cache, returning a pair of the final environment and result
      * in the monad `M`.
      */
    def runFetch[A, C <: DataSourceCache, M[_]](
      fa: Fetch[A],
      cache: C
    )(
      implicit
        MM: MonadError[M, Throwable],
      CC: Cache[C]
    ): M[(FetchEnv[C], A)] = fa.foldMap[FetchInterpreter[M, C]#f](interpreter).run(FetchEnv(cache))

    /**
      * Run a `Fetch` with the given cache, returning the final environment in the monad `M`.
      */
    def runEnv[A, C <: DataSourceCache, M[_]](
      fa: Fetch[A],
      cache: C = InMemoryCache.empty
    )(
      implicit
        MM: MonadError[M, Throwable],
      CC: Cache[C]
    ): M[FetchEnv[C]] = MM.map(runFetch[A, C, M](fa, cache)(MM, CC))(_._1)

    /**
      * Run a `Fetch` with the given cache, the result in the monad `M`.
      */
    def run[A, C <: DataSourceCache, M[_]](
      fa: Fetch[A],
      cache: C = InMemoryCache.empty
    )(
      implicit
        MM: MonadError[M, Throwable],
      CC: Cache[C]
    ): M[A] = MM.map(runFetch[A, C, M](fa, cache)(MM, CC))(_._2)
  }

  def interpreter[C <: DataSourceCache, I, E <: Env[C], M[_]](
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): FetchOp ~> FetchInterpreter[M, C]#f = {
    def dedupeIds[I, A, M[_]](ids: List[I], ds: DataSource[I, A, M], cache: C) = {
      ids.distinct.filterNot(i => CC.get(cache, ds.identity(i)).isDefined)
    }

    new (FetchOp ~> FetchInterpreter[M, C]#f) {
      def apply[A](fa: FetchOp[A]): FetchInterpreter[M, C]#f[A] = {
        StateT[M, FetchEnv[C], A] { env: FetchEnv[C] => fa match {
          case FetchError(e) => MM.raiseError(e)
          case Cached(a) => MM.pure((env, a))
          case Concurrent(manies) => {
            val startRound = System.nanoTime()
            val cache = env.cache
            val sources = manies.map(_.ds)
            val ids = manies.map(_.as)

            val sourcesAndIds = (sources zip ids).map({
              case (ds, ids) => (
                ds,
                dedupeIds[I, A, M](ids.asInstanceOf[List[I]], ds.asInstanceOf[DataSource[I, A, M]], cache)
              )
            }).filterNot({
              case (_, ids) => ids.isEmpty
            })

            if (sourcesAndIds.isEmpty)
              MM.pure((env, env.asInstanceOf[A]))
            else
              MM.flatMap(sourcesAndIds.map({
                case (ds, as) => ds.asInstanceOf[DataSource[I, A, M]].fetch(as.asInstanceOf[List[I]])
              }).sequence)((results: List[Map[_, _]]) => {
                val endRound = System.nanoTime()
                val newCache = (sources zip results).foldLeft(cache)((accache, resultset) => {
                  val (ds, resultmap) = resultset
                  val tresults = resultmap.asInstanceOf[Map[I, A]]
                  val tds = ds.asInstanceOf[DataSource[I, A, M]]
                  CC.cacheResults[I, A, M](accache, tresults, tds)
                })
                val newEnv = env.next(
                  newCache,
                  Round(
                    cache,
                    "Concurrent",
                    ConcurrentRound(
                      sourcesAndIds.map({
                        case (ds, as) => (ds.name, as)
                      }).toMap
                    ),
                    startRound,
                    endRound
                  ),
                  Nil
                )
                MM.pure((newEnv, newEnv.asInstanceOf[A]))
              })
          }
          case FetchOne(id, ds) => {
            val startRound = System.nanoTime()
            val cache = env.cache
            CC.get(cache, ds.identity(id)).fold[M[(FetchEnv[C], A)]](
              MM.flatMap(ds.fetch(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                val endRound = System.nanoTime()
                res.get(id.asInstanceOf[I]).fold[M[(FetchEnv[C], A)]](
                  MM.raiseError(
                    FetchFailure(
                      env.next(
                        cache,
                        Round(cache, ds.name, OneRound(id), startRound, endRound),
                        List(id)
                      )
                    )
                  )
                )(result => {
                  val endRound = System.nanoTime()
                  val newCache = CC.update(cache, ds.identity(id), result)
                  MM.pure(
                    (env.next(
                      newCache,
                      Round(cache, ds.name, OneRound(id), startRound, endRound),
                      List(id)
                    ), result))
                })
              })
            )(cached => {
              val endRound = System.nanoTime()
              MM.pure(
                (env.next(
                  cache,
                  Round(cache, ds.name, OneRound(id), startRound, endRound, true),
                  List(id)), cached.asInstanceOf[A]))
            })
          }
          case FetchMany(ids, ds) => {
            val startRound = System.nanoTime()
            val cache = env.cache
            val oldIds = ids.distinct
            val newIds = dedupeIds[Any, Any, Any](ids, ds, cache)
            if (newIds.isEmpty)
              MM.pure(
                (env.next(
                  cache,
                  Round(cache, ds.name, ManyRound(ids), startRound, System.nanoTime(), true),
                  newIds
                ), ids.flatMap(id => CC.get(cache, ds.identity(id)))))
            else {
              MM.flatMap(ds.fetch(newIds).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                val endRound = System.nanoTime()
                ids.map(i => res.get(i.asInstanceOf[I])).sequence.fold[M[(FetchEnv[C], A)]](
                  MM.raiseError(
                    FetchFailure(
                      env.next(
                        cache,
                        Round(cache, ds.name, ManyRound(ids), startRound, endRound),
                        newIds
                      )
                    )
                  )
                )(results => {
                  val endRound = System.nanoTime()
                  val newCache = CC.cacheResults[I, A, M](cache, res, ds.asInstanceOf[DataSource[I, A, M]])
                  val someCached = oldIds.size == newIds.size
                  MM.pure(
                    (env.next(
                      newCache,
                      Round(cache, ds.name, ManyRound(ids), startRound, endRound, someCached),
                      newIds
                    ), results))
                })
              })
            }
          }
        }
        }
      }
    }
  }

  implicit val fetchCartesian: Cartesian[Fetch] = new Cartesian[Fetch]{
    def product[A, B](fa: Fetch[A], fb: Fetch[B]): Fetch[(A, B)] = Fetch.join(fa, fb)
  }
}



