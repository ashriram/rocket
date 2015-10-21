package rocket

import Chisel._
import uncore._
import Util._

trait HasL1CacheParameters extends HasCacheParameters with HasCoreParameters {
  val outerDataBeats = p(TLKey(p(TLId))).dataBeats
  val outerDataBits = p(TLKey(p(TLId))).dataBitsPerBeat
  val refillCyclesPerBeat = outerDataBits/rowBits
  val refillCycles = refillCyclesPerBeat*outerDataBeats
}

class ICacheReq(implicit p: Parameters) extends CoreBundle()(p) {
  val idx = UInt(width = pgIdxBits)
  val ppn = UInt(width = ppnBits) // delayed one cycle
  val kill = Bool() // delayed one cycle
}

class ICacheResp(implicit p: Parameters) extends CoreBundle()(p) with HasL1CacheParameters {
  val data = Bits(width = coreInstBits)
  val datablock = Bits(width = rowBits)
}

class ICache(implicit p: Parameters) extends CoreModule()(p) with HasL1CacheParameters {
  val io = new Bundle {
    val req = Valid(new ICacheReq).flip
    val resp = Decoupled(new ICacheResp)
    val invalidate = Bool(INPUT)
    val mem = new ClientUncachedTileLinkIO
  }
  require(isPow2(nSets) && isPow2(nWays))
  require(isPow2(coreInstBytes))
  require(pgIdxBits >= untagBits)

  val s_ready :: s_request :: s_refill_wait :: s_refill :: Nil = Enum(UInt(), 4)
  val state = Reg(init=s_ready)
  val invalidated = Reg(Bool())
  val stall = !io.resp.ready
  val rdy = Wire(Bool())

  val refill_addr = Reg(UInt(width = paddrBits))
  val s1_any_tag_hit = Bool()

  val s1_valid = Reg(init=Bool(false))
  val s1_pgoff = Reg(UInt(width = pgIdxBits))
  val s1_addr = Cat(io.req.bits.ppn, s1_pgoff).toUInt
  val s1_tag = s1_addr(tagBits+untagBits-1,untagBits)

  val s0_valid = io.req.valid || s1_valid && stall
  val s0_pgoff = Mux(s1_valid && stall, s1_pgoff, io.req.bits.idx)

  s1_valid := io.req.valid && rdy || s1_valid && stall && !io.req.bits.kill
  when (io.req.valid && rdy) {
    s1_pgoff := io.req.bits.idx
  }

  val out_valid = s1_valid && !io.req.bits.kill && state === s_ready
  val s1_idx = s1_addr(untagBits-1,blockOffBits)
  val s1_offset = s1_addr(blockOffBits-1,0)
  val s1_hit = out_valid && s1_any_tag_hit
  val s1_miss = out_valid && !s1_any_tag_hit
  rdy := state === s_ready && !s1_miss

  when (s1_valid && state === s_ready && s1_miss) {
    refill_addr := s1_addr
  }
  val refill_tag = refill_addr(tagBits+untagBits-1,untagBits)

  val narrow_grant = FlowThroughSerializer(io.mem.grant, refillCyclesPerBeat)
  val (refill_cnt, refill_wrap) = Counter(narrow_grant.fire(), refillCycles) //TODO Zero width wire
  val refill_done = state === s_refill && refill_wrap
  narrow_grant.ready := Bool(true)

  val repl_way = if (isDM) UInt(0) else LFSR16(s1_miss)(log2Up(nWays)-1,0)
  val entagbits = code.width(tagBits)
  val tag_array = SeqMem(Vec(Bits(width = entagbits), nWays), nSets)
  val tag_rdata = tag_array.read(s0_pgoff(untagBits-1,blockOffBits), !refill_done && s0_valid)
  when (refill_done) {
    val tag = code.encode(refill_tag).toUInt
    tag_array.write(s1_idx, Vec.fill(nWays)(tag), Vec.tabulate(nWays)(repl_way === _))
  }

  val vb_array = Reg(init=Bits(0, nSets*nWays))
  when (refill_done && !invalidated) {
    vb_array := vb_array.bitSet(Cat(repl_way, s1_idx), Bool(true))
  }
  when (io.invalidate) {
    vb_array := Bits(0)
    invalidated := Bool(true)
  }
  val s1_disparity = Vec.fill(nWays){Bool()}
  for (i <- 0 until nWays)
    when (s1_valid && s1_disparity(i)) { vb_array := vb_array.bitSet(Cat(UInt(i), s1_idx), Bool(false)) }

  val s1_tag_match = Vec.fill(nWays){Bool()}
  val s1_tag_hit = Vec.fill(nWays){Bool()}
  val s1_dout = Vec.fill(nWays){(Bits())}

  for (i <- 0 until nWays) {
    val s1_vb = !io.invalidate && vb_array(Cat(UInt(i), s1_pgoff(untagBits-1,blockOffBits))).toBool
    val tag_out = tag_rdata(i)
    val s1_tag_disparity = code.decode(tag_out).error
    when (s1_valid && rdy && !stall) {
    }
    s1_tag_match(i) := tag_out(tagBits-1,0) === s1_tag
    s1_tag_hit(i) := s1_vb && s1_tag_match(i)
    s1_disparity(i) := s1_vb && (s1_tag_disparity || code.decode(s1_dout(i)).error)
  }
  s1_any_tag_hit := s1_tag_hit.reduceLeft(_||_) && !s1_disparity.reduceLeft(_||_)

  for (i <- 0 until nWays) {
    val data_array = SeqMem(Bits(width = code.width(rowBits)), nSets*refillCycles)
    val wen = narrow_grant.valid && repl_way === UInt(i)
    when (wen) {
      val e_d = code.encode(narrow_grant.bits.data).toUInt
      if(refillCycles > 1) data_array.write(Cat(s1_idx, refill_cnt), e_d)
      else data_array.write(s1_idx, e_d)
    }
    val s0_raddr = s0_pgoff(untagBits-1,blockOffBits-(if(refillCycles > 1) refill_cnt.getWidth else 0))
    val s1_rdata = data_array.read(s0_raddr, !wen && s0_valid)
    // if s1_tag_match is critical, replace with partial tag check
    s1_dout(i) := 0
    when (s1_valid && rdy && !stall && (Bool(isDM) || s1_tag_match(i))) { s1_dout(i) := s1_rdata }
  }
  io.resp.bits.datablock := Mux1H(s1_tag_hit, s1_dout)

  // output signals
  io.resp.valid := s1_hit
  io.mem.acquire.valid := (state === s_request)
  io.mem.acquire.bits := GetBlock(addr_block = refill_addr >> blockOffBits)

  // control state machine
  switch (state) {
    is (s_ready) {
      when (s1_miss) { state := s_request }
      invalidated := Bool(false)
    }
    is (s_request) {
      when (io.mem.acquire.ready) { state := s_refill_wait }
    }
    is (s_refill_wait) {
      when (io.mem.grant.valid) { state := s_refill }
    }
    is (s_refill) {
      when (refill_done) { state := s_ready }
    }
  }
}
