use super::*;
use debug_core::{BurstFrames, WatchFrames};
use std::sync::{Arc, Mutex};

#[derive(Default)]
struct RecordedState {
    words: HashMap<u64, u32>,
    writes: Vec<(u64, u32)>,
    cleared_bps: Vec<u64>,
    resumes: usize,
    disconnects: usize,
    halted: bool,
    burst: BurstFrames,
    watch_burst: Vec<Vec<Vec<u8>>>,
}

struct RecordingBackend {
    shared: bool,
    state: Arc<Mutex<RecordedState>>,
}

impl DebugBackend for RecordingBackend {
    fn connect(&mut self) -> Result<(), BackendError> {
        Ok(())
    }
    fn disconnect(&mut self) {
        self.state.lock().unwrap().disconnects += 1;
    }
    fn is_connected(&self) -> bool {
        true
    }
    fn is_shared(&self) -> bool {
        self.shared
    }
    fn supports_watch_interleave(&self) -> bool {
        true
    }
    fn read_bytes(&mut self, addr: u64, len: usize) -> Result<Vec<u8>, BackendError> {
        let state = self.state.lock().unwrap();
        Ok((0..len)
            .map(|i| {
                let address = addr + i as u64;
                let word = state.words.get(&(address & !3)).copied().unwrap_or(0);
                (word >> ((address & 3) * 8)) as u8
            })
            .collect())
    }
    fn write_bytes(&mut self, addr: u64, data: &[u8]) -> Result<(), BackendError> {
        assert_eq!(data.len(), 4);
        let value = u32::from_le_bytes(data.try_into().unwrap());
        let mut state = self.state.lock().unwrap();
        state.words.insert(addr, value);
        state.writes.push((addr, value));
        Ok(())
    }
    fn halt(&mut self) -> Result<(), BackendError> {
        self.state.lock().unwrap().halted = true;
        Ok(())
    }
    fn resume(&mut self) -> Result<(), BackendError> {
        let mut state = self.state.lock().unwrap();
        state.halted = false;
        state.resumes += 1;
        Ok(())
    }
    fn step(&mut self) -> Result<(), BackendError> {
        Ok(())
    }
    fn reset(&mut self) -> Result<(), BackendError> {
        Ok(())
    }
    fn reset_and_halt(&mut self) -> Result<(), BackendError> {
        self.halt()
    }
    fn is_halted(&mut self) -> Result<bool, BackendError> {
        Ok(self.state.lock().unwrap().halted)
    }
    fn set_breakpoint(&mut self, _addr: u64) -> Result<(), BackendError> {
        Ok(())
    }
    fn clear_breakpoint(&mut self, addr: u64) -> Result<(), BackendError> {
        self.state.lock().unwrap().cleared_bps.push(addr);
        Ok(())
    }
    fn read_core_register(&mut self, _name: &str) -> Result<u64, BackendError> {
        Ok(0x08001200)
    }
    fn scope_burst(
        &mut self,
        _blocks: &[(u64, usize)],
        _count: usize,
        _interval: Duration,
    ) -> Result<BurstFrames, BackendError> {
        Ok(self.state.lock().unwrap().burst.clone())
    }
    fn scope_burst_watch(
        &mut self,
        _blocks: &[(u64, usize)],
        _watch_blocks: &[(u64, usize)],
        _watch_every: usize,
        _count: usize,
        _interval: Duration,
    ) -> Result<(BurstFrames, WatchFrames), BackendError> {
        let state = self.state.lock().unwrap();
        Ok((state.burst.clone(), state.watch_burst.clone()))
    }
}

fn engine(shared: bool) -> (Engine, Arc<Mutex<RecordedState>>, Receiver<Event>) {
    let (_, cmd_rx) = unbounded();
    let (event_tx, event_rx) = unbounded();
    let state = Arc::new(Mutex::new(RecordedState::default()));
    let mut engine = Engine::new(cmd_rx, event_tx);
    engine.backend = Some(Box::new(RecordingBackend {
        shared,
        state: state.clone(),
    }));
    (engine, state, event_rx)
}

fn set_comparator(state: &mut RecordedState, slot: usize, addr: u64, function: u32) {
    let offset = slot as u64 * 0x10;
    state.words.insert(DWT_COMP + offset, addr as u32);
    state.words.insert(DWT_MASK + offset, 2);
    state.words.insert(DWT_FUNCTION + offset, function);
}

#[test]
fn shared_shutdown_preserves_external_halt_and_all_foreign_comparators() {
    let (mut engine, state, _) = engine(true);
    {
        let mut state = state.lock().unwrap();
        state.halted = true;
        for slot in 0..MAX_WATCHPOINTS {
            set_comparator(
                &mut state,
                slot,
                0x20001000 + slot as u64 * 4,
                DWT_FN_DATA_RW_4B,
            );
        }
    }
    engine.shutdown = true;
    engine.run();
    let state = state.lock().unwrap();
    assert!(
        state.writes.is_empty(),
        "unconfigured scope monitoring must not write DWT"
    );
    assert!(state.halted);
    assert_eq!(state.resumes, 0);
    assert_eq!(state.disconnects, 1);
}

#[test]
fn disconnect_clears_only_owned_slots_and_breakpoints_without_shared_resume() {
    let (mut engine, state, _) = engine(true);
    {
        let mut state = state.lock().unwrap();
        state.halted = true;
        set_comparator(&mut state, 0, 0x20001000, DWT_FN_DATA_RW_4B);
    }
    engine.program_watchpoints(&[0x20002000]);
    assert_eq!(engine.owned_watchpoints, vec![(1, 0x20002000)]);
    engine.applied_bps.push(0x08001000);
    engine.temp_bps.push(0x08002000);
    state.lock().unwrap().writes.clear();
    engine.handle_command(Command::Disconnect);
    let state = state.lock().unwrap();
    assert_eq!(state.writes, vec![(DWT_FUNCTION + 0x10, 0)]);
    assert_eq!(state.words[&DWT_FUNCTION], DWT_FN_DATA_RW_4B);
    assert_eq!(state.cleared_bps, vec![0x08001000, 0x08002000]);
    assert_eq!(state.resumes, 0);
    assert!(state.halted);
    assert!(engine.owned_watchpoints.is_empty());
}

#[test]
fn externally_replaced_owned_comparator_is_preserved_when_clearing() {
    let (mut engine, state, _) = engine(true);
    engine.program_watchpoints(&[0x20002000]);
    {
        let mut state = state.lock().unwrap();
        set_comparator(&mut state, 0, 0x20003000, 5);
        state.writes.clear();
    }
    engine.program_watchpoints(&[]);
    let state = state.lock().unwrap();
    assert!(state.writes.is_empty());
    assert_eq!(state.words[&DWT_COMP], 0x20003000);
    assert_eq!(state.words[&DWT_FUNCTION], 5);
    assert!(engine.owned_watchpoints.is_empty());
}

#[test]
fn watchpoint_allocation_uses_only_free_slots_and_reports_shortage() {
    let (mut engine, state, events) = engine(true);
    set_comparator(&mut state.lock().unwrap(), 0, 0x20001000, DWT_FN_DATA_RW_4B);
    engine.program_watchpoints(&[0x20002000, 0x20002004, 0x20002008, 0x2000200c]);
    assert_eq!(
        engine.owned_watchpoints,
        vec![(1, 0x20002000), (2, 0x20002004), (3, 0x20002008)]
    );
    let state = state.lock().unwrap();
    assert_eq!(state.words[&DWT_COMP], 0x20001000);
    assert!(!state
        .writes
        .iter()
        .any(|(addr, _)| *addr >= DWT_COMP && *addr <= DWT_FUNCTION));
    assert!(events.try_iter().any(
        |event| matches!(event, Event::Log { message } if message.contains("1 个数据观察点未设置"))
    ));
}

#[test]
fn halt_hit_uses_allocated_slot_instead_of_list_index() {
    let (mut engine, state, events) = engine(true);
    {
        let mut state = state.lock().unwrap();
        set_comparator(&mut state, 0, 0x20001000, DWT_FN_DATA_RW_4B);
        set_comparator(&mut state, 1, 0x20001004, DWT_FN_DATA_RW_4B);
    }
    engine.program_watchpoints(&[0x20002000]);
    assert_eq!(engine.owned_watchpoints, vec![(2, 0x20002000)]);
    let mut functions = vec![0; MAX_WATCHPOINTS];
    functions[2] = DWT_FN_DATA_RW_4B | (1 << 24);
    engine.pending_dwt = Some((0, functions));
    state.lock().unwrap().writes.clear();
    engine.on_halted();
    assert!(events.try_iter().any(|event| matches!(event, Event::Log { message } if message.contains("数据观察点命中 0x20002000"))));
    assert_eq!(
        state.lock().unwrap().writes,
        vec![
            (DWT_FUNCTION + 0x20, 0),
            (DWT_FUNCTION + 0x20, DWT_FN_DATA_RW_4B)
        ]
    );
}

#[test]
fn exclusive_disconnect_keeps_resume_behavior() {
    let (mut engine, state, _) = engine(false);
    state.lock().unwrap().halted = true;
    engine.handle_command(Command::Disconnect);
    assert_eq!(state.lock().unwrap().resumes, 1);
}

#[test]
fn replacement_connection_cleans_previous_backend_before_connecting() {
    let (mut engine, state, _) = engine(true);
    engine.program_watchpoints(&[0x20002000]);
    state.lock().unwrap().halted = true;
    engine.handle_command(Command::Connect(ConnectParams::default()));
    let state = state.lock().unwrap();
    assert_eq!(state.words[&DWT_FUNCTION], 0);
    assert_eq!(state.disconnects, 1);
    assert_eq!(state.resumes, 0);
    assert!(engine.backend.as_ref().unwrap().is_connected());
}

#[test]
fn partial_and_total_read_failures_remain_explicit_in_scope_frames() {
    let (mut engine, state, _) = engine(true);
    let targets = vec![
        ScopeTarget {
            addr: 0x20000000,
            size: 4,
        },
        ScopeTarget {
            addr: 0x20001000,
            size: 4,
        },
    ];
    engine.handle_command(Command::UpdateScopeTargets(targets));
    engine.scope_discard_until = None;
    state.lock().unwrap().burst = vec![
        (
            Duration::from_millis(1),
            vec![1.25f32.to_le_bytes().to_vec(), vec![]],
        ),
        (Duration::from_millis(2), vec![vec![], vec![]]),
    ];
    engine.sample_scope();
    assert_eq!(engine.scope_buffer.len(), 2);
    assert_eq!(
        engine.scope_buffer[0].values["0x20000000"],
        1.25f32.to_le_bytes()
    );
    assert!(engine.scope_buffer[0].values["0x20001000"].is_empty());
    assert_eq!(engine.scope_buffer[1].values.len(), 2);
    assert!(engine.scope_buffer[1]
        .values
        .values()
        .all(|bytes| bytes.is_empty()));
}

#[test]
fn totally_failed_burst_keeps_missing_samples_and_backs_off() {
    let (mut engine, state, events) = engine(true);
    engine.handle_command(Command::UpdateScopeTargets(vec![ScopeTarget {
        addr: 0x20000000,
        size: 4,
    }]));
    engine.scope_discard_until = None;
    state.lock().unwrap().burst = vec![(Duration::from_millis(1), vec![vec![]])];
    engine.sample_scope();
    assert_eq!(engine.scope_buffer.len(), 1);
    assert!(engine.scope_buffer[0].values["0x20000000"].is_empty());
    // 自适应退避：首次整批失败 2ms 快速重试（固定 50ms 会让瞬时失败白吃 50ms 空档）
    assert!(engine.next_scope >= Instant::now() + Duration::from_millis(1));
    assert!(engine.next_scope < Instant::now() + Duration::from_millis(10));
    // 连续失败指数升级，50ms 封顶
    for _ in 0..8 {
        engine.sample_scope();
    }
    assert!(engine.next_scope >= Instant::now() + Duration::from_millis(40));
    assert!(engine.next_scope < Instant::now() + Duration::from_millis(120));
    assert!(events
        .try_iter()
        .any(|event| matches!(event, Event::Error { message } if message.contains("已记录缺样"))));
}

#[test]
fn fail_backoff_escalates_and_caps_at_50ms() {
    assert_eq!(Engine::fail_backoff(1), Duration::from_millis(2));
    assert_eq!(Engine::fail_backoff(2), Duration::from_millis(4));
    assert_eq!(Engine::fail_backoff(3), Duration::from_millis(8));
    assert_eq!(Engine::fail_backoff(5), Duration::from_millis(32));
    assert_eq!(Engine::fail_backoff(6), Duration::from_millis(50));
    assert_eq!(Engine::fail_backoff(100), Duration::from_millis(50));
}

#[test]
fn drain_commands_budget_caps_per_round_and_reports_pending() {
    let (cmd_tx, cmd_rx) = unbounded();
    let (event_tx, _events) = unbounded();
    let mut eng = Engine::new(cmd_rx, event_tx);
    for _ in 0..40 {
        cmd_tx.send(Command::SetSymbolRefs(Vec::new())).unwrap();
    }
    // 首轮预算（32 条）耗尽 → 报告仍有积压；次轮消费剩余 8 条 → 队列已空
    assert!(eng.drain_commands(), "首轮预算耗尽应返回 true（仍有积压）");
    assert!(!eng.drain_commands(), "次轮消费剩余后应返回 false（队列空）");
    assert!(
        !eng.shutdown,
        "命令应被真实消费（SetSymbolRefs 已生效）且不触发关机"
    );
}

#[test]
fn watch_interleave_emits_latest_watch_data_and_sets_flag() {
    let (mut engine, state, events) = engine(true);
    engine.handle_command(Command::UpdateScopeTargets(vec![ScopeTarget {
        addr: 0x20000000,
        size: 4,
    }]));
    engine.handle_command(Command::UpdateWatchTargets(vec![MemTarget {
        id: "live_val".into(),
        addr: 0x20000004,
        size: 4,
        auto_refresh: true,
    }]));
    engine.handle_command(Command::SetScopeFreq(1000.0));
    engine.scope_discard_until = None;
    // 插帧监视数据：块 [0x20000004+4B] 一帧有效一帧失败（空字节）
    *state.lock().unwrap() = RecordedState {
        burst: vec![
            (Duration::from_millis(1), vec![2.5f32.to_le_bytes().to_vec()]),
            (Duration::from_millis(2), vec![3.5f32.to_le_bytes().to_vec()]),
        ],
        watch_burst: vec![
            vec![1.0f32.to_le_bytes().to_vec()],
            vec![vec![]],
            vec![2.0f32.to_le_bytes().to_vec()],
        ],
        ..Default::default()
    };
    engine.sample_scope();
    assert!(engine.watch_interleave_active, "示波 1kHz > 监视 5Hz 应插帧");
    let mut saw_watch = false;
    for event in events.try_iter() {
        if let Event::WatchData { values, halted } = event {
            saw_watch = true;
            assert!(!halted);
            assert_eq!(values["live_val"], 2.0f32.to_le_bytes(), "只推最后一帧监视值");
        }
    }
    assert!(saw_watch, "插帧监视读应产生 WatchData");
    // 示波帧正常入缓冲
    assert_eq!(engine.scope_buffer.len(), 2);
}

#[test]
fn repeated_targets_and_frequency_do_not_restart_warmup_or_drop_frames() {
    let (mut engine, _, _) = engine(true);
    let targets = vec![ScopeTarget {
        addr: 0x20000000,
        size: 4,
    }];
    engine.handle_command(Command::UpdateScopeTargets(targets.clone()));
    engine.handle_command(Command::SetScopeFreq(200.0));
    engine.scope_discard_until = None;
    engine.scope_buffer.push(ScopeSample {
        t: 1.0,
        values: HashMap::new(),
    });
    engine.handle_command(Command::UpdateScopeTargets(targets));
    engine.handle_command(Command::SetScopeFreq(200.0));
    assert!(engine.scope_discard_until.is_none());
    assert_eq!(engine.scope_buffer.len(), 1);
}
