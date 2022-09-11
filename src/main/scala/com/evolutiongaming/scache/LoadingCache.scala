package com.evolutiongaming.scache

import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource}
import cats.syntax.all._

import scala.util.control.NoStackTrace


object LoadingCache {

  private sealed abstract class LoadingCache

  private[scache] def of[F[_] : Concurrent, K, V](
    map: EntryRefs[F, K, V],
  ): Resource[F, Cache[F, K, V]] = {
    for {
      ref   <- Resource.eval(Ref[F].of(map))
      cache <- of(ref)
    } yield cache
  }


  private[scache] def of[F[_] : Concurrent, K, V](
    ref: Ref[F, EntryRefs[F, K, V]],
  ): Resource[F, Cache[F, K, V]] = {
    Resource.make {
      apply(ref).pure[F]
    } { cache =>
      cache.clear.flatten
    }
  }


  private[scache] def apply[F[_] : Concurrent, K, V](
    ref: Ref[F, EntryRefs[F, K, V]],
  ): Cache[F, K, V] = {

    val ignoreThrowable = (_: Throwable) => ()

    case object NoneError extends RuntimeException with NoStackTrace

    def loadedOf(value: V, release: Option[F[Unit]]) = {
      EntryRef.Entry.Loaded(
        value,
        release.map { _.handleError(ignoreThrowable) })
    }

    def put1(key: K, loaded: EntryRef.Entry.Loaded[F, V]) = {
      0.tailRecM { counter =>
        ref
          .access
          .flatMap { case (entries, set) =>
            entries
              .get(key)
              .fold {
                EntryRef
                  .loaded(loaded)
                  .flatMap { entry =>
                    set(entries.updated(key, entry)).map {
                      case true  => none[V].pure[F].asRight[Int]
                      case false => (counter + 1).asLeft[F[Option[V]]]
                    }
                  }
              } { entry =>
                entry
                  .put(loaded)
                  .map { _.asRight[Int] }
              }
          }
      }
    }

    def getOrUpdateReleasable1(key: K)(loaded: => F[EntryRef.Entry.Loaded[F, V]]) = {
      0.tailRecM { counter =>
        ref
          .access
          .flatMap { case (entries, set) =>
            entries
              .get(key)
              .fold {
                val cleanup = ref.update { _ - key }
                EntryRef
                  .loading(loaded, cleanup)
                  .flatMap { case (entry, load) =>
                    set(entries.updated(key, entry))
                      .flatMap {
                        case true  => load.map { _.asRight[Int] }
                        case false => (counter + 1).asLeft[V].pure[F]
                      }
                      .uncancelable
                  }
              } { entry =>
                entry
                  .get
                  .map { _.asRight[Int] }
              }
          }
      }
    }

    new LoadingCache with Cache[F, K, V] {

      def get(key: K) = {
        ref
          .get
          .flatMap { _.get(key).traverse(_.get) }
          .handleError { _ => none[V] }
      }

      def getOrElse(key: K, default: => F[V]) = {
        for {
          stored <- get(key)
          result <- stored.fold(default)(_.pure[F])
        } yield {
          result
        }
      }

      def getOrUpdate(key: K)(value: => F[V]) = {
        getOrUpdateReleasable1(key) {
          value.map { loadedOf(_, none) }
        }
      }

      def getOrUpdateOpt(key: K)(value: => F[Option[V]]) = {
        getOrUpdateReleasable1(key) {
          for {
            value <- value
            value <- value.fold { NoneError.raiseError[F, V] } { _.pure[F] }
          } yield {
            loadedOf(value, none)
          }
        }
          .map { _.some }
          .recover { case NoneError => none }
      }

      def getOrUpdateReleasable(key: K)(value: => F[Releasable[F, V]]) = {
        getOrUpdateReleasable1(key) {
          value.map { value => loadedOf(value.value, value.release.some) }
        }
      }

      def getOrUpdateReleasableOpt(key: K)(value: => F[Option[Releasable[F, V]]]) = {
        getOrUpdateReleasable1(key) {
          for {
            value <- value
            value <- value.fold { NoneError.raiseError[F, Releasable[F, V]] } { _.pure[F] }
          } yield {
            loadedOf(value.value, value.release.some)
          }
        }
          .map { _.some }
          .recover { case NoneError => none }
      }


      def put(key: K, value: V) = {
        def loaded = loadedOf(value, none)
        put1(key, loaded)
      }


      def put(key: K, value: V, release: F[Unit]) = {
        def loaded = loadedOf(value, release.some)
        put1(key, loaded)
      }

      def contains(key: K) = {
        ref
          .get
          .map { _.contains(key) }
      }


      def size = {
        ref
          .get
          .map { _.size }
      }


      def keys = {
        ref
          .get
          .map { _.keySet }
      }


      def values = {
        ref
          .get
          .map { entries =>
            entries.map { case (k, v) => (k, v.get) }
          }
      }

      def values1 = {
        ref
          .get
          .flatMap { entries =>
            entries
              .toList
              .traverse { case (k, v) =>
                v
                  .getLoaded
                  .map {
                    case Some(v) => (k, v.asRight[F[V]])
                    case None    => (k, v.get.asLeft[V])
                  }
              }
              .map { _.toMap }
          }
      }


      def remove(key: K) = {
        0.tailRecM { counter =>
          ref
            .access
            .flatMap { case (entries, set) =>
              entries
                .get(key)
                .fold {
                  none[V]
                    .pure[F]
                    .asRight[Int]
                    .pure[F]
                } { entry =>
                  set(entries - key)
                    .flatMap {
                      case true  =>
                        entry
                          .release
                          .flatMap { _ =>
                            entry
                              .get
                              .map { _.some }
                          }
                          .handleError { _ => none[V] }
                          .start
                          .map { fiber =>
                            fiber
                              .join
                              .asRight[Int]
                          }
                      case false =>
                        (counter + 1)
                          .asLeft[F[Option[V]]]
                          .pure[F]
                    }
                    .uncancelable
                }
            }
        }
      }


      def clear = {
        ref
          .getAndSet(EntryRefs.empty)
          .flatMap { entryRefs =>
            entryRefs
              .values
              .toList
              .foldMapM { _.release.start }
          }
          .uncancelable
          .map { _.join }
      }
    }
  }


  type EntryRefs[F[_], K, V] = Map[K, EntryRef[F, V]]

  object EntryRefs {
    def empty[F[_], K, V]: EntryRefs[F, K, V] = Map.empty
  }
}