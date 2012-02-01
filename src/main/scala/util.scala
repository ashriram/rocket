package Top
{

import Chisel._
import Node._
import scala.math._

object foldR
{
  def apply[T <: Bits](x: Seq[T], f: (T, T) => T): T =
    if (x.length == 1) x(0) else f(x(0), foldR(x.slice(1, x.length), f))
}

object log2up
{
  def apply(in: Int) = if (in == 1) 1 else ceil(log(in)/log(2)).toInt
}

object ispow2
{
  def apply(in: Int) = in > 0 && ((in & (in-1)) == 0)
}

object FillInterleaved
{
  def apply(n: Int, in: Bits) =
  {
    var out = Fill(n, in(0))
    for (i <- 1 until in.getWidth)
      out = Cat(Fill(n, in(i)), out)
    out
  }
}

object Reverse
{
  def apply(in: Bits) =
  {
    var out = in(in.getWidth-1)
    for (i <- 1 until in.getWidth)
      out = Cat(in(in.getWidth-i-1), out)
    out
  }
}

object OHToUFix
{
  def apply(in: Bits): UFix = 
  {
    val out = MuxCase( UFix(0), (0 until in.getWidth).map( i => (in(i).toBool, UFix(i))))
    out.toUFix
  }
}

object UFixToOH
{
  def apply(in: UFix, width: Int): Bits =
  {
    (UFix(1) << in(log2up(width)-1,0))
  }
}

object LFSR16
{
  def apply(increment: Bool = Bool(true)) =
  {
    val width = 16
    val lfsr = Reg(resetVal = UFix(1, width))
    when (increment) { lfsr <== Cat(lfsr(0)^lfsr(2)^lfsr(3)^lfsr(5), lfsr(width-1,1)).toUFix }
    lfsr
  }
}

object Mux1H 
{
//TODO: cloning in(0) is unsafe if other elements have different widths, but
//is that even allowable?
  def apply [T <: Data](n: Int, sel: Vec[Bool], in: Vec[T]): T = {
    MuxCase(in(0), (0 until n).map( i => (sel(i), in(i))))
//    val mux = (new Mux1H(n)){ in(0).clone }
//    mux.io.sel <> sel
//    mux.io.in <> in
//    mux.io.out.asInstanceOf[T]
  }

  def apply [T <: Data](n: Int, sel: Seq[Bool], in: Vec[T]): T = {
    MuxCase(in(0), (0 until n).map( i => (sel(i), in(i))))
//    val mux = (new Mux1H(n)){ in(0).clone }
//    for(i <- 0 until n) {
//      mux.io.sel(i) := sel(i)
//    }
//    mux.io.in <> in.asOutput
//    mux.io.out.asInstanceOf[T]
  }

  def apply [T <: Data](n: Int, sel: Bits, in: Vec[T]): T = {
    MuxCase(in(0), (0 until n).map( i => (sel(i).toBool, in(i))))
//    val mux = (new Mux1H(n)){ in(0).clone }
//    for(i <- 0 until n) {
//      mux.io.sel(i) := sel(i).toBool
//    }
//    mux.io.in := in
//    mux.io.out
  }
}

class Mux1H [T <: Data](n: Int)(gen: => T) extends Component
{
  val io = new Bundle {
    val sel = Vec(n) { Bool(dir = INPUT) }
    val in  = Vec(n) { gen }.asInput
    val out = gen.asOutput
  }

  if (n > 2) {
    var out = io.in(0).toBits & Fill(gen.getWidth, io.sel(0))
    for (i <- 1 to n-1)
      out = out | (io.in(i).toBits & Fill(gen.getWidth, io.sel(i)))
    io.out := out
  } else if (n == 2) {
    io.out := Mux(io.sel(1), io.in(1), io.in(0))
  } else {
    io.out := io.in(0)
  }
}









class ioDecoupled[T <: Data]()(data: => T) extends Bundle
{
  val valid = Bool(INPUT)
  val ready = Bool(OUTPUT)
  val bits  = data.asInput
}

class ioArbiter[T <: Data](n: Int)(data: => T) extends Bundle {
  val in  = Vec(n) { (new ioDecoupled()) { data } }
  val out = (new ioDecoupled()) { data }.flip()
}

class Arbiter[T <: Data](n: Int)(data: => T) extends Component {
  val io = new ioArbiter(n)(data)

  io.in(0).ready := io.out.ready
  for (i <- 1 to n-1) {
    io.in(i).ready := !io.in(i-1).valid && io.in(i-1).ready
  }

  var dout = io.in(n-1).bits
  for (i <- 1 to n-1)
    dout = Mux(io.in(n-1-i).valid, io.in(n-1-i).bits, dout)

  var vout = io.in(0).valid
  for (i <- 1 to n-1)
    vout = vout || io.in(i).valid

  vout <> io.out.valid
  dout <> io.out.bits
}

class ioPriorityDecoder(in_width: Int, out_width: Int) extends Bundle
{
  val in  = UFix(in_width, INPUT);
  val out = Bits(out_width, OUTPUT);
}

class priorityDecoder(width: Int) extends Component
{
  val in_width = ceil(log10(width)/log10(2)).toInt;  
  val io = new ioPriorityEncoder(in_width, width);
  val l_out = Wire() { Bits() };
  
  for (i <- 0 to width-1) {
    when (io.in === UFix(i, in_width)) {
      l_out <== Bits(1,1) << UFix(i);
    }
  }
  
  l_out <== Bits(0, width);
  io.out := l_out;
}

class ioPriorityEncoder(in_width: Int, out_width: Int) extends Bundle
{
  val in  = Bits(in_width, INPUT);
  val out = UFix(out_width, OUTPUT);
}

class priorityEncoder(width: Int) extends Component
{
  val out_width = ceil(log10(width)/log10(2)).toInt;  
  val io = new ioPriorityDecoder(width, out_width);
  val l_out = Wire() { UFix() };
  
  for (i <- 0 to width-1) {
    when (io.in(i).toBool) {
      l_out <== UFix(i, out_width);
    }
  }
  
  l_out <== UFix(0, out_width);
  io.out := l_out;
}

}
