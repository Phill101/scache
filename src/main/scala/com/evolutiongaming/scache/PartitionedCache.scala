package com.evolutiongaming.scache

import cats.syntax.all.*
import cats.{Applicative, Monad, Parallel}
import com.evolutiongaming.catshelper.ParallelHelper.*
import cats.kernel.{CommutativeMonoid, Monoid}

object PartitionedCache {

  private sealed abstract class PartitionedCache

  @deprecated("use `apply1` instead", "4.1.1")
  def apply[F[_]: Monad, K, V](
    partitions: Partitions[K, Cache[F, K, V]]
  ): Cache[F, K, V] = {
    implicit val parallel: Parallel[F] = Parallel.identity
    apply1(partitions)
  }

  def apply1[F[_]: Monad: Parallel, K, V](
    partitions: Partitions[K, Cache[F, K, V]]
  ): Cache[F, K, V] = {

    implicit def monoidUnit: Monoid[F[Unit]] = Applicative.monoid[F, Unit]

    new PartitionedCache with Cache[F, K, V] {

      def get(key: K) = {
        partitions
          .get(key)
          .get(key)
      }

      def get1(key: K) = {
        partitions
          .get(key)
          .get1(key)
      }

      def getOrElse(key: K, default: => F[V]) = {
        partitions
          .get(key)
          .getOrElse(key, default)
      }

      def getOrUpdate(key: K)(value: => F[V]) = {
        partitions
          .get(key)
          .getOrUpdate(key)(value)
      }

      def getOrUpdateOpt(key: K)(value: => F[Option[V]]) = {
        partitions
          .get(key)
          .getOrUpdateOpt(key)(value)
      }

      def getOrUpdateReleasable(key: K)(value: => F[Releasable[F, V]]) = {
        partitions
          .get(key)
          .getOrUpdateReleasable(key)(value)
      }

      def getOrUpdateReleasableOpt(key: K)(value: => F[Option[Releasable[F, V]]]) = {
        partitions
          .get(key)
          .getOrUpdateReleasableOpt(key)(value)
      }

      def put(key: K, value: V) = {
        partitions
          .get(key)
          .put(key, value)
      }

      def put(key: K, value: V, release: F[Unit]) = {
        partitions
          .get(key)
          .put(key, value, release)
      }

      def contains(key: K) = {
        partitions
          .get(key)
          .contains(key)
      }

      def size = {
        partitions
          .values
          .foldMapM(_.size)
      }

      def keys = {
        partitions
          .values
          .foldLeftM(Set.empty[K]) { (keys, cache) =>
            cache
              .keys
              .map { _ ++ keys }
          }
      }

      def values = {
        partitions
          .values
          .foldLeftM(Map.empty[K, F[V]]) { (values, cache) =>
            cache
              .values
              .map { _ ++ values }
          }
      }

      def values1 = {
        partitions
          .values
          .foldLeftM(Map.empty[K, Either[F[V], V]]) { (values, cache) =>
            cache
              .values1
              .map { _ ++ values }
          }
      }

      def remove(key: K) = {
        partitions
          .get(key)
          .remove(key)
      }

      def clear = {
        partitions
          .values
          .foldMapM { _.clear }
      }

      def foldMap[A: CommutativeMonoid](f: (K, Either[F[V], V]) => F[A]) = {
        partitions
          .values
          .foldMapM { _.foldMap(f) }
      }

      def foldMapPar[A: CommutativeMonoid](f: (K, Either[F[V], V]) => F[A]) = {
        partitions
          .values
          .parFoldMap1 { _.foldMap(f) }
      }
    }
  }
}