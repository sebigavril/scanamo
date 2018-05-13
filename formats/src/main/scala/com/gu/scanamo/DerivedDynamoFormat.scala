package com.gu.scanamo

import cats.data.ValidatedNel
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.traverse._
import cats.syntax.validated._
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.gu.scanamo.error._
import magnolia._
import scala.language.experimental.macros

import collection.JavaConverters._

trait DerivedDynamoFormat {

  type Valid[A] = ValidatedNel[PropertyReadError, A]

  def combine[T](cc: CaseClass[DynamoFormat, T]): DynamoFormat[T] = new DynamoFormat[T] {
    private final def decodeField[A](m: Map[String, AttributeValue])(p: Param[DynamoFormat, A]): Valid[p.PType] =
      Either.fromOption(m.get(p.label), PropertyReadError(p.label, MissingProperty))
        .flatMap(v => p.typeclass.read(v).leftMap(PropertyReadError(p.label, _)))
        .toValidatedNel

    private final def decode(av: AttributeValue): Valid[Seq[Any]] =
      Option(av.getM).map(_.asScala.toMap) match {
        case Some(m) => cc.parameters.toList.traverse(decodeField(m)(_))
        case None => PropertyReadError(cc.typeName.full, NoPropertyOfType("M", av)).invalidNel
      }

    def read(av: AttributeValue): Either[DynamoReadError, T] = 
      decode(av).fold(fe => Left(InvalidPropertiesError(fe)), fa => Right(cc.rawConstruct(fa)))

    def write(t: T): AttributeValue =
      new AttributeValue().withM(cc.parameters.map { p => 
        p.label -> p.typeclass.write(p.dereference(t)) 
      }.toMap.asJava)
  }

  def dispatch[T](sealedTrait: SealedTrait[DynamoFormat, T]): DynamoFormat[T] =
    new DynamoFormat[T] {
      def read(av: AttributeValue): Either[DynamoReadError, T] = ???
      def write(t: T): AttributeValue = ???
    }
  
  implicit def gen[T]: DynamoFormat[T] = macro Magnolia.gen[T]
}
