package com.mpc.scalats.core

/**
  * Created by Milosz on 09.06.2016.
  */

import scala.collection.immutable.ListSet

import scala.reflect.runtime.universe._

// TODO: Keep namespace using fullName from the Type
final class ScalaParser(logger: Logger) {

  import ScalaModel._

  def parseTypes(types: List[Type]): ListSet[TypeDef] =
    parse(types, ListSet.empty[Type], ListSet.empty[TypeDef])

  // ---

  private def parseType(tpe: Type): Option[TypeDef] = tpe match {
    case _: SingleTypeApi =>
      parseObject(tpe)

    case _ if (tpe.getClass.getName contains "ModuleType"/*Workaround*/) =>
      parseObject(tpe)

    case _ if tpe.typeSymbol.isClass => {
      val classSym = tpe.typeSymbol.asClass

      if (classSym.isTrait && classSym.isSealed && tpe.typeParams.isEmpty) {
        parseSealedUnion(tpe)
      } else if (isCaseClass(tpe) && !isAnyValChild(tpe)) {
        parseCaseClass(tpe)
      } else {
        Option.empty[TypeDef]
      }
    }

    case _ => {
      logger.warning(s"Unsupported Scala type: $tpe")
      Option.empty[TypeDef]
    }
  }

  private object Field {
    def unapply(m: MethodSymbol): Option[MethodSymbol] = m match {
      case m: MethodSymbol if (!m.isAbstract && m.isPublic && !m.isImplicit &&
          m.paramLists.forall(_.isEmpty) &&
          {
            val n = m.name.toString
            !(n.contains("$") || n.startsWith("<"))
          } && 
          m.overrides.forall { o =>
            val declaring = o.owner.fullName

            !declaring.startsWith("java.") &&
            !declaring.startsWith("scala.")
          }) => Some(m)

      case _ => None
    }
  }

  private def parseObject(tpe: Type): Option[CaseObject] = {

    println(s"parsing sealed union ${tpe.typeSymbol.name.toString}")

    val members = tpe.decls.collect {
      case Field(m) =>
        println(s"found members ${m.name.toString}")
        member(m, List.empty)
    }

    Some(CaseObject(
      tpe.typeSymbol.name.toString stripSuffix ".type",
      ListSet.empty ++ members))
  }

  private def parseSealedUnion(tpe: Type): Option[SealedUnion] = {
    // TODO: Check & warn there is no type parameters for a union type

    println(s"parsing sealed union ${tpe.typeSymbol.name.toString}")

    // Members
    val members = tpe.decls.collect {
      case m: MethodSymbol if (m.isAbstract && m.isPublic && !m.isImplicit &&
          !m.name.toString.endsWith("$")) => member(m, List.empty)
    }

    directKnownSubclasses(tpe) match {
      case possibilities @ (_ :: _ ) =>
        Some(SealedUnion(
          tpe.typeSymbol.name.toString,
          ListSet.empty ++ members,
          parseTypes(possibilities)))

      case _ => Option.empty[SealedUnion]
    }
  }

  private def parseCaseClass(caseClassType: Type): Option[CaseClass] = {

    println(s"parsing case class ${caseClassType.typeSymbol.name.toString}")

    val typeParams = caseClassType.typeConstructor.
      dealias.typeParams.map(_.name.decodedName.toString)

    println(s"found typeParams ${typeParams.mkString(", ")}")

    // Members
    val members = caseClassType.members.collect {
      case Field(m) if m.isCaseAccessor =>
        println(s"found members ${m.name.toString}")
        member(m, typeParams)
    }.toList

    val values = caseClassType.decls.collect {
      case Field(m) =>
        println(s"found values ${m.name.toString}")
        member(m, typeParams)
    }.filterNot(members.contains)

    Some(CaseClass(
      caseClassType.typeSymbol.name.toString,
      ListSet.empty ++ members,
      ListSet.empty ++ values,
      ListSet.empty ++ typeParams
    ))
  }

  @inline private def member(
    sym: MethodSymbol, typeParams: List[String]
  ) = TypeMember(sym.name.toString, getTypeRef(
    sym.returnType.map(_.dealias), typeParams.toSet))

  @annotation.tailrec
  private def parse(types: List[Type], examined: ListSet[Type], parsed: ListSet[TypeDef]): ListSet[TypeDef] = types match {
    case scalaType :: tail => {
      if (!examined.contains(scalaType) &&
        !scalaType.typeSymbol.isParameter) {

        val relevantMemberSymbols = scalaType.members.collect {
          case m: MethodSymbol if m.isCaseAccessor => m
        }

        val memberTypes = relevantMemberSymbols.map(
          _.typeSignature.map(_.dealias) match {
            case NullaryMethodType(resultType) => resultType
            case t => t.map(_.dealias)
          })

        val typeArgs = scalaType match {
          case t: scala.reflect.runtime.universe.TypeRef =>
            t.args

          case _ => List.empty[Type]
        }

        parse(
          memberTypes ++: typeArgs ++: tail,
          examined + scalaType,
          parsed ++ parseType(scalaType))

      } else {
        parse(tail, examined + scalaType, parsed ++ parseType(scalaType))
      }
    }

    case _ => parsed
  }

  // TODO: resolve from implicit (typeclass)
  private def getTypeRef(scalaType: Type, typeParams: Set[String]): TypeRef = {
    scalaType.typeSymbol.name.toString match {
      case "Int" | "Byte" | "Short" =>
        IntRef
      case "Long" =>
        LongRef
      case "Double" =>
        DoubleRef
      case "Boolean" =>
        BooleanRef
      case "String" =>
        StringRef
      case "List" | "Seq" | "Set" => // TODO: Traversable
        val innerType = scalaType.asInstanceOf[scala.reflect.runtime.universe.TypeRef].args.head
        SeqRef(getTypeRef(innerType, typeParams))
      case "Option" =>
        val innerType = scalaType.asInstanceOf[scala.reflect.runtime.universe.TypeRef].args.head
        OptionRef(getTypeRef(innerType, typeParams))
      case "LocalDate" =>
        DateRef
      case "Instant" | "Timestamp" | "LocalDateTime" | "ZonedDateTime" =>
        DateTimeRef
      case typeParam if typeParams.contains(typeParam) =>
        TypeParamRef(typeParam)
      case _ if isAnyValChild(scalaType) =>
        getTypeRef(scalaType.members.filter(!_.isMethod).map(_.typeSignature).head, Set())
      case _ if isCaseClass(scalaType) =>
        val caseClassName = scalaType.typeSymbol.name.toString
        val typeArgs = scalaType.asInstanceOf[scala.reflect.runtime.universe.TypeRef].args
        val typeArgRefs = typeArgs.map(getTypeRef(_, typeParams))

        CaseClassRef(caseClassName, ListSet.empty ++ typeArgRefs)

      case "Either" => {
        val innerTypeL = scalaType.asInstanceOf[scala.reflect.runtime.universe.TypeRef].args.head
        val innerTypeR = scalaType.asInstanceOf[scala.reflect.runtime.universe.TypeRef].args.last

        UnionRef(ListSet(
          getTypeRef(innerTypeL, typeParams),
          getTypeRef(innerTypeR, typeParams)))
      }

      case "Map" =>
        val keyType = scalaType.asInstanceOf[scala.reflect.runtime.universe.TypeRef].args.head
        val valueType = scalaType.asInstanceOf[scala.reflect.runtime.universe.TypeRef].args.last
        MapRef(getTypeRef(keyType, typeParams), getTypeRef(valueType, typeParams))
      case unknown =>
        //println(s"type ref $typeName umkown")
        UnknownTypeRef(unknown)
    }
  }

  @inline private def isCaseClass(scalaType: Type): Boolean =
    scalaType.typeSymbol.isClass && scalaType.typeSymbol.asClass.isCaseClass

  @inline private def isAnyValChild(scalaType: Type): Boolean =
    scalaType <:< typeOf[AnyVal]

  private def directKnownSubclasses(tpe: Type): List[Type] = {
    // Workaround for SI-7046: https://issues.scala-lang.org/browse/SI-7046
    val tpeSym = tpe.typeSymbol.asClass

    @annotation.tailrec
    def allSubclasses(path: Traversable[Symbol], subclasses: Set[Type]): Set[Type] = path.headOption match {
      case Some(cls: ClassSymbol) if (
        tpeSym != cls && cls.selfType.baseClasses.contains(tpeSym)) => {
        val newSub: Set[Type] = if (!cls.isCaseClass) {
          logger.warning(s"cannot handle class ${cls.fullName}: no case accessor")
          Set.empty
        } else if (cls.typeParams.nonEmpty) {
          logger.warning(s"cannot handle class ${cls.fullName}: type parameter not supported")
          Set.empty
        } else Set(cls.selfType)

        allSubclasses(path.tail, subclasses ++ newSub)
      }

      case Some(o: ModuleSymbol) if (
        o.companion == NoSymbol && // not a companion object
          o.typeSignature.baseClasses.contains(tpeSym)) =>
        allSubclasses(path.tail, subclasses + o.typeSignature)

      case Some(o: ModuleSymbol) if (
        o.companion == NoSymbol // not a companion object
      ) => allSubclasses(path.tail, subclasses)

      case Some(_) => allSubclasses(path.tail, subclasses)

      case _ => subclasses
    }

    if (tpeSym.isSealed && tpeSym.isAbstract) {
      allSubclasses(tpeSym.owner.typeSignature.decls, Set.empty).toList
    } else List.empty
  }
}
