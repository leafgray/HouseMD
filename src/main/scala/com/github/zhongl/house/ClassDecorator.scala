package com.github.zhongl.house

import org.objectweb.asm._
import org.objectweb.asm.Opcodes._
import commons.AdviceAdapter
import org.objectweb.asm.Type._

object ClassDecorator {

  def decorate(classfileBuffer: Array[Byte], toDecorateMethodRegexs: Traversable[String]) = {
    val cr: ClassReader = new ClassReader(classfileBuffer)
    val cw: ClassWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS)
    cr.accept(classAdapter(cw, toDecorateMethodRegexs), ClassReader.EXPAND_FRAMES)
    cw.toByteArray
  }

  def classAdapter(cw: ClassWriter, methodRegexs: Traversable[String]) =
    new ClassAdapter(cw) {

      override def visit(
                          version: Int,
                          access: Int,
                          name: String,
                          signature: String,
                          superName: String,
                          interfaces: Array[String]) {
        className = name.replace("/", ".")
        super.visit(version, access, name, signature, superName, interfaces)
      }

      override def visitMethod(
                                access: Int,
                                name: String,
                                desc: String,
                                signature: String,
                                exceptions: Array[String]) = {
        val mv = super.visitMethod(access, name, desc, signature, exceptions)
        if ((mv != null && containsMethod(name))) methodAdapter(mv, access, name, desc) else mv
      }

      private[this] def containsMethod(methodName: String) = !methodRegexs.find((className + "." + methodName).matches).isEmpty

      private[this] def methodAdapter(mv: MethodVisitor, access: Int, methodName: String, desc: String): MethodAdapter =
        new AdviceAdapter(mv, access, methodName, desc) {
          override def visitMaxs(maxStack: Int, maxLocals: Int) {
            mark(end)
            catchException(start, end, Type.getType(classOf[Throwable]))
            dup()
            invokeStatic(AdviceProxy.TYPE, AdviceProxy.EXIT)
            throwException()
            super.visitMaxs(maxStack, maxLocals)
          }

          protected override def onMethodEnter() {
            push(className)
            push(methodName)
            push(methodDesc)
            loadThisOrPushNullIfIsStatic()
            loadArgArray()
            invokeStatic(AdviceProxy.TYPE, AdviceProxy.ENTRY)
            mark(start)
          }

          protected override def onMethodExit(opcode: Int) {
            if (opcode != ATHROW) {
              prepareResultBy(opcode)
              invokeStatic(AdviceProxy.TYPE, AdviceProxy.EXIT)
            }
          }

          private[this] def isStaticMethod = (methodAccess & ACC_STATIC) != 0

          private[this] def loadThisOrPushNullIfIsStatic() {if (isStaticMethod) pushNull() else loadThis()}

          private[this] def prepareResultBy(opcode: Int) {
            opcode match {
              case RETURN            => pushNull() // void
              case ARETURN           => dup() // object
              case LRETURN | DRETURN => dup2(); box(getReturnType(methodDesc)) // long or double
              case _                 => dup(); box(getReturnType(methodDesc)) // object or boolean or byte or char or short or int
            }
          }

          private[this] def pushNull() {push(null.asInstanceOf[Type])}

          private[this] val start = new Label
          private[this] val end   = new Label
        }

      private[this] var className: String = _

    }
}