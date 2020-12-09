/*
 * Copyright 2020 FINGO sp. z o.o.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package info.fingo.spata.schema

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Sync
import fs2.{Pipe, Stream}
import info.fingo.spata.Record
import info.fingo.spata.text.StringParser
import info.fingo.spata.util.Logger
import shapeless.{::, DepFn2, HList, HNil}
import shapeless.labelled.{field, FieldType}

class TypedRecord[+A](val data: A, val lineNum: Int, val rowNum: Int)

object TypedRecord {
  def apply[A](data: A, lineNum: Int, rowNum: Int): TypedRecord[A] = new TypedRecord[A](data, lineNum, rowNum)
}

class CSVSchema[L <: HList: SchemaEnforcer](columns: L) {

  def validate[F[_]: Sync: Logger](implicit enforcer: SchemaEnforcer[L]): Pipe[F, Record, enforcer.Out] =
    (in: Stream[F, Record]) => in.map(r => validateRecord(r)(enforcer))

  private def validateRecord(r: Record)(implicit enforcer: SchemaEnforcer[L]): enforcer.Out = enforcer(columns, r)
}

object CSVSchema {
  def apply[L <: HList: SchemaEnforcer](columns: L) = new CSVSchema(columns)
}

class Column[K <: StrSng, V: StringParser](val name: K, val validators: Seq[Validator[V]]) {
  def validate(record: Record): Validated[FieldFlaw, FieldType[K, V]] = {
    val typeValidated =
      Validated.fromEither(record.get(name)).leftMap(e => FieldFlaw(name, NotParsed(e.messageCode, e)))
    val fullyValidated = typeValidated.andThen { v =>
      validators
        .map(_.validate(v).leftMap(FieldFlaw(name, _)))
        .foldLeft(typeValidated)((prev, curr) => prev.andThen(_ => curr))
    }
    fullyValidated.map(field[K](_))
  }
}

object Column {
  def apply[A: StringParser](name: String): Column[name.type, A] = new Column(name, Nil)
  def apply[A: StringParser](name: String, validators: Seq[Validator[A]]): Column[name.type, A] =
    new Column(name, validators)
}

trait SchemaEnforcer[L <: HList] extends DepFn2[L, Record] {
  type Out <: VR[HList]
}

object SchemaEnforcer {
  type Aux[I <: HList, O <: VR[HList]] = SchemaEnforcer[I] { type Out = O }

  private def empty(record: Record) =
    Validated.valid[InvalidRecord, TypedRecord[HNil]](TypedRecord(HNil, record.lineNum, record.rowNum))

  implicit val nil: Aux[HNil, VR[HNil]] = new SchemaEnforcer[HNil] {
    override type Out = VR[HNil]
    override def apply(columns: HNil, record: Record): Out = empty(record)
  }

  implicit def cons[K <: StrSng, V, T <: HList, TTR <: HList](
    implicit tailEnforcer: Aux[T, VR[TTR]]
  ): Aux[Column[K, V] :: T, VR[FieldType[K, V] :: TTR]] =
    new SchemaEnforcer[Column[K, V] :: T] {
      override type Out = VR[FieldType[K, V] :: TTR]
      override def apply(columns: Column[K, V] :: T, record: Record): Out = {
        val column = columns.head
        val validated = column.validate(record)
        val tailV = tailEnforcer(columns.tail, record)
        validated match {
          case Valid(vv) => tailV.map(tv => TypedRecord(vv :: tv.data, record.lineNum, record.rowNum))
          case Invalid(iv) =>
            val flaws = tailV match {
              case Valid(_) => Nil
              case Invalid(ivt) => ivt.flaws
            }
            Validated.invalid[InvalidRecord, TypedRecord[FieldType[K, V] :: TTR]](
              InvalidRecord(record, iv :: flaws)
            )
        }
      }
    }

  def enforce[L <: HList](columns: L, record: Record)(implicit enforcer: SchemaEnforcer[L]): enforcer.Out =
    enforcer(columns, record)
}
