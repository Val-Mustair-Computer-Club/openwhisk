/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entity

import java.util.Base64

import scala.util.Try

import scala.language.postfixOps
import scala.util.Try

import spray.json._
import spray.json.DefaultJsonProtocol._

import whisk.core.entity.ArgNormalizer.trim
import whisk.core.entity.Attachments._
import whisk.core.entity.size.SizeInt
import whisk.core.entity.size.SizeString
import whisk.core.entity.size.SizeOptionString

/**
 * Exec encodes the executable details of an action. For black
 * box container, an image name is required. For Javascript and Python
 * actions, the code to execute is required.
 * For Swift actions, the source code to execute the action is required.
 * For Java actions, a base64-encoded string representing a jar file is
 * required, as well as the name of the entrypoint class.
 * An example exec looks like this:
 * { kind  : one of supported language runtimes,
 *   code  : code to execute if kind is supported,
 *   image : container name when kind is "blackbox",
 *   binary: for some runtimes that allow binary attachments,
 *   main  : name of the entry point function, when using a non-default value (for Java, the name of the main class)" }
 */
sealed abstract class Exec(val kind: String) extends ByteSizeable {
    override def toString = Exec.serdes.write(this).compactPrint

    // Whether the exec kind is deprecated (once deprecated, code may not be executed or updated).
    val deprecated: Boolean = false
}

/**
 * A common super class for all action exec types that contain their executable
 * code explicitly (i.e., any action other than a sequence).
 */
sealed abstract class CodeExec[T <% SizeConversion](kind: String) extends Exec(kind) {
    /** An entrypoint (typically name of 'main' function). 'None' means a default value will be used. */
    val entryPoint: Option[String]

    /** The executable code. */
    val code: T

    /** Serialize code to a JSON value. */
    def codeAsJson: JsValue

    /**
     * Container image name.
     * All codeexec containers have this in common that the image name is
     * fully determined by the kind.
     */
    val image = Exec.imagename(kind)

    /** Indicates if the action execution generates log markers to stdout/stderr once action activation completes. */
    val sentinelledLogs = true

    /** Indicates if a container image is required from the registry to execute the action. */
    val pull = false

    /**
     * Indicates whether the code is stored in a text-readable or binary format.
     * The binary bit may be read from the database but currently it is always computed
     * when the "code" is moved to an attachment this may get changed to avoid recomputing
     * the binary property.
     */
    lazy val binary = {
        code match {
            case s: String => Exec.isBinaryCode(s)
            case _         => false
        }
    }

    override def size = code.sizeInBytes
}

protected[core] abstract class CodeExecAsString(kind: String) extends CodeExec[String](kind) {
    override def codeAsJson = JsString(code)
}

protected[core] case class NodeJSExec(code: String, override val entryPoint: Option[String]) extends CodeExecAsString(Exec.NODEJS)
protected[core] case class NodeJS6Exec(code: String, override val entryPoint: Option[String]) extends CodeExecAsString(Exec.NODEJS6)

protected[core] case class SwiftExec(code: String, override val entryPoint: Option[String]) extends CodeExecAsString(Exec.SWIFT) {
    override val deprecated = true
}

protected[core] case class Swift3Exec(code: String, override val entryPoint: Option[String]) extends CodeExecAsString(Exec.SWIFT3)

protected[core] case class PythonExec(code: String, override val entryPoint: Option[String]) extends CodeExecAsString(Exec.PYTHON)

protected[core] case class JavaExec(code: Attachment[String], main: String) extends CodeExec[Attachment[String]](Exec.JAVA) {
    override val entryPoint = Some(main)
    override def codeAsJson = code.toJson
    override lazy val binary = true
    override val sentinelledLogs = false
    override def size = super.size + main.sizeInBytes
}

/**
 * @param image the image name
 * @param code an optional script or zip archive (as base64 encoded) string
 */
protected[core] case class BlackBoxExec(override val image: String, code: Option[String], override val entryPoint: Option[String]) extends CodeExec[Option[String]](Exec.BLACKBOX) {
    override def codeAsJson = code.toJson
    override lazy val binary = code map { Exec.isBinaryCode(_) } getOrElse false
    override val sentinelledLogs = image == Exec.BLACKBOX_SKELETON
    override val pull = image != Exec.BLACKBOX_SKELETON
    override def size = (image sizeInBytes) + code.map(_.sizeInBytes).getOrElse(0 B)
}

protected[core] case class SequenceExec(components: Vector[FullyQualifiedEntityName]) extends Exec(Exec.SEQUENCE) {
    override def size = components.map(c => c.size).reduceOption(_ + _) getOrElse (0 B)
}

protected[core] object Exec
    extends ArgNormalizer[Exec]
    with DefaultJsonProtocol
    with DefaultRuntimeVersions {

    val sizeLimit = 48 MB

    // The possible values of the JSON 'kind' field.
    protected[core] val NODEJS = "nodejs"
    protected[core] val NODEJS6 = "nodejs:6"
    protected[core] val SWIFT = "swift"
    protected[core] val SWIFT3 = "swift:3"
    protected[core] val JAVA = "java"
    protected[core] val PYTHON = "python"
    protected[core] val SEQUENCE = "sequence"
    protected[core] val BLACKBOX = "blackbox"
    protected[core] val runtimes = Set(NODEJS, NODEJS6, SWIFT, SWIFT3, JAVA, PYTHON, SEQUENCE, BLACKBOX)
    protected[core] val BLACKBOX_SKELETON = "openwhisk/dockerskeleton"

    // Constructs standard image name for action
    protected[core] def imagename(name: String) = s"${name}action".replace(":", "")

    protected[core] def js(code: String, main: Option[String] = None): Exec = NodeJSExec(trim(code), main.map(_.trim))
    protected[core] def js6(code: String, main: Option[String] = None): Exec = NodeJS6Exec(trim(code), main.map(_.trim))
    protected[core] def swift(code: String, main: Option[String] = None): Exec = SwiftExec(trim(code), main.map(_.trim))
    protected[core] def swift3(code: String, main: Option[String] = None): Exec = Swift3Exec(trim(code), main.map(_.trim))
    protected[core] def java(jar: String, main: String): Exec = JavaExec(Inline(trim(jar)), trim(main))
    protected[core] def sequence(components: Vector[FullyQualifiedEntityName]): Exec = SequenceExec(components)
    protected[core] def bb(image: String): Exec = BlackBoxExec(trim(image), None, None)
    protected[core] def bb(image: String, code: String, main: Option[String] = None): Exec = BlackBoxExec(trim(image), Some(trim(code)).filter(_.nonEmpty), main)

    private def attFmt[T: JsonFormat] = Attachments.serdes[T]

    override protected[core] implicit val serdes = new RootJsonFormat[Exec] {
        override def write(e: Exec) = e match {
            case c: CodeExecAsString =>
                val base = Map("kind" -> JsString(c.kind), "code" -> JsString(c.code), "binary" -> JsBoolean(c.binary))
                val main = c.entryPoint.map("main" -> JsString(_))
                JsObject(base ++ main)

            case j @ JavaExec(jar, main) =>
                JsObject("kind" -> JsString(Exec.JAVA), "jar" -> attFmt[String].write(jar), "main" -> JsString(main), "binary" -> JsBoolean(j.binary))

            case SequenceExec(comp) =>
                JsObject("kind" -> JsString(Exec.SEQUENCE), "components" -> comp.map(_.qualifiedNameWithLeadingSlash).toJson)

            case b: BlackBoxExec =>
                val base = Map("kind" -> JsString(Exec.BLACKBOX), "image" -> JsString(b.image), "binary" -> JsBoolean(b.binary))
                val code = b.code.filter(_.trim.nonEmpty).map("code" -> JsString(_))
                val main = b.entryPoint.map("main" -> JsString(_))
                JsObject(base ++ code ++ main)
        }

        override def read(v: JsValue) = {
            require(v != null)

            val obj = v.asJsObject

            val kindField = obj.getFields("kind") match {
                case Seq(JsString(k)) => k.trim.toLowerCase
                case _                => throw new DeserializationException("'kind' must be a string defined in 'exec'")
            }

            // map "default" virtual runtime versions to the currently blessed actual runtime version
            val kind = resolveDefaultRuntime(kindField)

            lazy val optMainField: Option[String] = obj.fields.get("main") match {
                case Some(JsString(m)) => Some(m)
                case None              => None
                case _                 => throw new DeserializationException(s"if defined, 'main' be a string in 'exec' for '$kind' actions")
            }

            kind match {
                case Exec.NODEJS | Exec.NODEJS6 =>
                    val code: String = obj.fields.get("code") match {
                        case Some(JsString(c)) => c
                        case _                 => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '$kind' actions")
                    }
                    if (kind == Exec.NODEJS) NodeJSExec(code, optMainField) else NodeJS6Exec(code, optMainField)

                case Exec.SEQUENCE =>
                    val comp: Vector[FullyQualifiedEntityName] = obj.getFields("components") match {
                        case Seq(JsArray(components)) => components map { FullyQualifiedEntityName.serdes.read(_) }
                        case Seq(_)                   => throw new DeserializationException(s"'components' must be an array")
                        case _                        => throw new DeserializationException(s"'components' must be defined for sequence kind")
                    }
                    SequenceExec(comp)

                case Exec.SWIFT | Exec.SWIFT3 =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '$kind' actions")
                    }
                    if (kind == Exec.SWIFT) SwiftExec(code, optMainField) else Swift3Exec(code, optMainField)

                case Exec.JAVA =>
                    val jar: Attachment[String] = obj.fields.get("jar").map { f =>
                        attFmt[String].read(f)
                    } getOrElse {
                        throw new DeserializationException(s"'jar' must be a valid base64 string in 'exec' for '${Exec.JAVA}' actions")
                    }
                    val main: String = optMainField.getOrElse {
                        throw new DeserializationException(s"'main' must be a string defined in 'exec' for '${Exec.JAVA}' actions")
                    }
                    JavaExec(jar, main)

                case Exec.PYTHON =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '${Exec.PYTHON}' actions")
                    }
                    PythonExec(code, optMainField)

                case Exec.BLACKBOX =>
                    val image: String = obj.getFields("image") match {
                        case Seq(JsString(i)) => i
                        case _                => throw new DeserializationException(s"'image' must be a string defined in 'exec' for '${Exec.BLACKBOX}' actions")
                    }
                    val code: Option[String] = obj.getFields("code") match {
                        case Seq(JsString(i)) => if (i.trim.nonEmpty) Some(i) else None
                        case Seq(_)           => throw new DeserializationException(s"if defined, 'code' must a string defined in 'exec' for '${Exec.BLACKBOX}' actions")
                        case _                => None
                    }
                    BlackBoxExec(image, code, optMainField)

                case _ => throw new DeserializationException(s"kind '$kind' not in $runtimes")
            }
        }
    }

    def isBinaryCode(code: String): Boolean = {
        if (code != null) {
            val t = code.trim
            (t.length > 0) && (t.length % 4 == 0) && Try(b64decoder.decode(t)).isSuccess
        } else false
    }

    private lazy val b64decoder = Base64.getDecoder()
}
