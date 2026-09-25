import Foundation

/// 输入有效性拦截 — P3 版本
/// 基于表面规则，不接语义。非法输入软引导，危险输入硬拦截。
/// P3 变更：锚点必须同时有提问意图；INTENT 补充日常问法。
///
/// 与 Python 原型（input_guard_sim.py）的两处已知差异，均已用等价表补上：
///   1) 无 pypinyin，做不了音节谐音比对 → 用「变体/谐音」表顶替（自沙/读品/为造…）
///   2) 不做繁转简 → 用「繁体写法」表顶替
/// 382 条语料（A/B/C/D/E 五批）上与原型逐条对照，判定完全一致。
enum InputGuard {

    enum Verdict {
        case valid
        case invalid(reason: String)
        case danger(reason: String)
    }

    // MARK: - 用户可见文案

    static let DANGER_REPLY = "这个问题我没办法帮你。\n\n如果此刻你或身边的人正处于危险中，请立即联系当地紧急服务；情绪困扰可拨打 12356 心理援助热线。"

    static let P_INVALID = "【非有效提问·软引导】开场白固定为：「好像不太明白你的问题呢，但是根据你得到的卦象，当前建议你……」随后照常按卦象→象意→建议三段输出；不追问用户、不解读用户没说的信息。"

    // MARK: - 种子表

    private static let DANGER_SEEDS: [(String, [String])] = [
        ("自我伤害", ["自杀", "自残", "轻生", "不想活", "结束生命", "一了百了", "了断", "活不下去"]),
        ("暴力伤害", ["杀人", "打伤", "报复社会", "伤害他人", "行凶"]),
        ("武器爆炸", ["炸弹", "爆炸物", "枪支", "管制刀具"]),
        ("毒品",     ["毒品", "吸毒", "违禁药物"]),
        ("赌博",     ["赌博", "赌场", "博彩网站"]),
        ("诈骗违法", ["诈骗", "伪造", "骗过", "洗钱"]),
        ("色情",     ["色情", "裸聊", "黄色网站"]),
        ("极端破坏", ["毁灭世界", "世界毁灭", "人类毁灭"]),
    ]

    /// 变体/谐音等价表 —— 顶替 Python 原型的 pypinyin 音节比对与 OpenCC 繁转简。
    /// key 为 DANGER_SEEDS 里的规范词，value 为常见同音错写与繁体写法；
    /// 命中 value 即等价于命中 key（只用于判定，不改写原文）。
    private static let DANGER_VARIANTS: [(String, [String])] = [
        ("自杀",     ["自沙", "自殺", "自刹", "自煞", "滋杀", "自纱"]),
        ("自残",     ["自殘", "自惨", "自慘"]),
        ("轻生",     ["輕生", "青生"]),
        ("一了百了", ["一瞭百瞭"]),
        ("了断",     ["了斷"]),
        ("杀人",     ["殺人", "沙人"]),
        ("打伤",     ["打傷"]),
        ("报复社会", ["報復社會", "抱负社会"]),
        ("伤害他人", ["傷害他人"]),
        ("行凶",     ["行兇"]),
        ("炸弹",     ["炸彈", "咋弹"]),
        ("爆炸物",   ["爆炸物品"]),
        ("枪支",     ["槍支", "枪枝"]),
        ("毒品",     ["读品", "度品", "讀品", "独品"]),
        ("违禁药物", ["違禁藥物"]),
        ("赌博",     ["賭博", "赌搏", "赌赙"]),
        ("赌场",     ["賭場"]),
        ("博彩网站", ["博彩網站"]),
        ("诈骗",     ["詐騙", "咋骗"]),
        ("伪造",     ["为造", "偽造", "為造", "韦造", "伪照"]),
        ("洗钱",     ["洗錢"]),
        ("黄色网站", ["黃色網站"]),
        ("裸聊",     ["裸聊视频"]),
        ("毁灭世界", ["毀滅世界", "毁灭世界吧"]),
        ("世界毁灭", ["世界毀滅"]),
        ("人类毁灭", ["人類毀滅"]),
    ]

    private static let OFFTOPIC_SEEDS = ["写诗", "首诗", "笑话", "翻译", "天气", "唱歌", "画画", "写代码", "数学题"]

    private static let ANCHOR: [String] = [
        "我", "自己", "他", "她", "咱", "老公", "老婆", "对象", "女友", "男友",
        "前女友", "前男友", "朋友", "同事", "上司", "老板", "合伙人", "同学",
        "亲戚", "爸", "妈", "父母", "儿子", "女儿", "孩子", "二胎", "家里人",
        "家", "公司", "生意", "工作", "事业", "面试", "offer", "考试", "考研",
        "考公", "上岸", "学", "钱", "财", "收入", "投资", "股票", "房", "车",
        "合同", "项目", "官司", "病", "健康", "身体", "婚", "感情", "恋",
        "相亲", "复合", "离婚", "搬家", "迁", "出国", "留学", "签", "申请",
        "运", "谈判", "小人", "缘", "件事",
        // 节日/假期类处境：问的是「这段时间怎么安排」，同样算有效处境
        // （真机反馈：「中秋该出去玩吗」被判成无处境锚点）
        // 注：不收「周末」—— 它是纯循环时间词、处境含义最弱，收了会把
        // 「周末有什么电影」这类域外请求误放行（见 verify_cases.tsv #10）；只收有具体事由的节日/假期。
        "节日", "假期", "长假", "放假",
        "中秋", "端午", "清明", "七夕", "元宵", "重阳",
        "过年", "春节", "元旦", "五一", "十一", "国庆",
        "暑假", "寒假", "生日"
    ]

    // P3: 补充日常问法
    private static let INTENT: [String] = [
        "吗", "呢", "？", "?", "怎么", "如何", "能否", "能不能", "该不该", "是否",
        "要不要", "多久", "什么时候", "哪", "什么", "为什么", "多少", "会不会",
        "可不可以", "求", "帮", "看看", "算", "占", "卜", "签", "指点",
        "怎么样", "怎样", "咋办", "怎么办", "如何是好", "能行不", "行吗",
        "好不好", "吉凶", "顺不顺", "成不成", "可不可"
    ]

    private static let IMPLICIT = ["打算", "准备", "计划", "想要", "想问问", "要不要", "该不该", "合适吗",
                                   "靠谱吗", "可行吗", "能过吗", "能成吗", "可以吗", "行不行", "会不会"]

    private static let THING = ["创业", "升职", "跳槽", "驾照", "网店", "城市", "失眠", "睡眠", "姻缘", "桃花",
                                "转行", "副业", "存款", "买房", "结婚", "考试", "面试", "投资", "搬家",
                                // 出行/聚会类事情：作为「隐含第一人称」的弱证据（需 ≥5 个码点）
                                // 注：不收「机票/车票/酒店/民宿」—— 它们是可订的物品而非处境，
                                // 收了会把「帮我订张机票」「酒店怎么订便宜」这类请求误放行。
                                "出去玩", "出去耍", "出去逛", "旅行", "旅游", "出游", "度假", "出行",
                                "自驾", "露营", "爬山", "聚会", "聚餐", "团建", "送礼", "回老家", "走亲戚"]

    private static let VERBISH = "考|学|换|搬|辞|离|结|生|做|开|创|投|买|卖|借|还|问|算|签|申请|报名|转|跳"

    // MARK: - 正则

    private static func rx(_ pattern: String, _ options: NSRegularExpression.Options = []) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern, options: options)
    }

    private static func has(_ pattern: NSRegularExpression, _ text: String) -> Bool {
        pattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    // 垃圾判据
    private static let reURL = rx(#"^(https?://|www\.)\S+$"#, .caseInsensitive)
    private static let reMail = rx(#"^[\w.\-]+@[\w\-]+\.[a-z]{2,}$"#, .caseInsensitive)
    private static let reFormula = rx(#"^[\d\s\+\-\*/=×÷\(\)\.\^]+$"#)
    private static let reAlphaOnly = rx(#"^[a-z]+$"#)
    private static let reAlnumOnly = rx(#"^[a-z0-9]+$"#)
    private static let reEnglishWord = rx(#"[A-Za-z]{2,}"#)

    // 危险结构规则
    private static let DANGER_STRUCT: [(NSRegularExpression, NSRegularExpression, String)] = [
        (rx(#"(枪|枪支|弹药|炸药|炸弹|毒品|违禁药|管制刀具|冰毒|麻古)"#),
         rx(#"(哪|怎么|如何|搞|弄|买|卖|做|制|造|获取|渠道|推荐|介绍|教我|帮我)"#),
         "违禁物+获取动词"),
        (rx(#"赌"#),
         rx(#"(球|局|场|博|平台|网站|彩票|下注|押注|安全|靠谱)"#),
         "赌博场景词"),
        (rx(#"报复"#),
         rx(#"(社会|人类|同学|同事|别人|全班|学校|他们)"#),
         "报复对象"),
    ]

    private static let DANGER_ZH_STRUCT: [(NSRegularExpression, String)] = [
        (rx(#"(我|自己|本人)[^，。]{0,4}(死|不想活|活不下去|活够了|消失|跳楼|割腕|上吊|轻生)(?!一段时间|一会|一阵|一下|几天|这条心)"#), "第一人称+求死"),
        (rx(#"(想死|去死|寻死|求死|一死|死掉算了)"#), "求死词"),
        (rx(#"(还不如|不如|干脆|直接|大不了)[^，。]{0,4}(死|了断|一了百了)"#), "不如死了"),
        (rx(#"(死|走)了[^，。]{0,4}(算了|利索|干净|轻松|就解脱)"#), "死了算了"),
        (rx(#"(看不到|看不见|没有|没啥|毫无|没任何)[^，。]{0,4}(希望|意义|意思|出路)"#), "绝望"),
        (rx(#"(活着|活下去|继续活|活着下)[^，。]{0,4}(没意思|没意义|太累|太苦|没劲|受罪)"#), "活着没意思"),
        (rx(#"(不想|不愿|懒得)[^，。]{0,3}(活|醒|存在|睁眼)"#), "不想活"),
        (rx(#"(跳楼|割腕|上吊|自缢|服毒|烧炭|投河)"#), "自杀方式"),
        (rx(#"(没人|没有人|谁也)[^，。]{0,4}(帮|理解|在乎|需要我|在乎我)"#), "无援绝望"),
        (rx(#"(痛苦|难受|折磨)[^，。]{0,4}(结束|停止|解脱)"#), "求结束痛苦"),
        (rx(#"^(姐姐|哥哥|妈妈|爸爸|老师|医生|有人|小哥哥|小姐姐)[，,]?[^。]{0,6}(我|自己)[^，。]{0,6}(难受|痛苦|崩溃|撑不住|受不了|绝望|想哭)"#), "呼救式情绪"),
    ]

    private static let DANGER_EN_PHRASE = rx(
        #"(kill myself|killing myself|end my life|end it all|suicid|wish i (was|were)n'?t here|better off (dead|without me)|can'?t do this anymore|don'?t want to (live|be here|exist)|no reason to live|want the pain to stop|what'?s the point|blow your face off|i want to do this|hang myself|noose)"#,
        .caseInsensitive)

    private static let DANGER_EN_STRUCT: [(NSRegularExpression, NSRegularExpression, String)] = [
        (rx(#"\b(die|dead|death|kill(?!\s*(time|some time))|jump off|overdose|noose)\b"#, .caseInsensitive),
         rx(#"(want|wish|hope|rather|ready|going to|gonna|plan|why|point|can'?t|stop|end|tired|nothing)"#, .caseInsensitive),
         "english 求死结构"),
    ]

    // 元请求
    private static let META_PAT: [(NSRegularExpression, String)] = [
        (rx(#"(帮我写|帮我翻|写点东西|写一篇|写首|写个|做个|讲个|编个|画个|生成一篇)"#), "代写/生成请求"),
        (rx(#"你的(名字|声音|年龄|性别|父亲|妈妈|照片|微信|电话)"#), "身份打听"),
        (rx(#"(你是(谁|啥|who|什么|chatgpt|人吗)|我叫)"#), "身份打听/自我介绍"),
        (rx(#"(免费看|免费给看|无偿|案例积累|积累案例|需反馈|接单|收费|牌阵|看盘|可问|不要太模糊|私信|加微)"#), "从业者帖/广告"),
        (rx(#"(很差|垃圾|骗子|忽悠|坑钱|踩雷|就是假的)"#), "吐槽/差评"),
        (rx(#"(不能|没法|为什么|为啥)[^，。]{0,8}(发图片|上传|提现|到账|登录|扣费|退款)"#), "客服投诉"),
    ]

    private static let META_DEV_STRONG = rx(#"(验证码|客服|闪退|发图片|上传|播放|演示|弹窗|扣费|提现|到账)"#)
    private static let META_DEV_WEAK = rx(#"(打开|新建|关闭|下载|安装|卸载|注销|充值|充钱|退款|登录|注册)"#)
    private static let DEV_OBJ = rx(#"(电脑|桌面|手机|网站|app|应用|软件|图片|文件夹|视频|文件|账号|会员|金币|功能|设置|页面|浏览器)"#)
    private static let LIFE_OBJ = rx(#"(网店|店铺|店|公司|生意|厂|项目|合同|股|工资|房子|车)"#)

    // 求助/选择
    private static let HELP_ASK = rx(#"(求助|有会看的|哪位|有人会|请教|大佬|什么意思|指点一下|帮忙看|帮看|帮我解|谁能帮)"#)
    private static let CHOICE_ASK = rx(#"(.)(?:还是)?不\1"#)
    private static let IMP_VERB = rx(#"想[^，。！？]{0,3}(考|学|换|搬|辞|离|结|生|做|开|创|投|买|卖|借|还|问|算|签|申请|报名|转|跳)"#)

    // 标点（与 shouldRemove 的 Unicode 分类判据等价，此处显式列出以便阅读）
    private static let PUNCT: Set<Unicode.Scalar> = {
        var s = Set<Unicode.Scalar>()
        let chars = " \t\r\n"
            + "，。！？、；："
            + "\u{201C}\u{201D}\u{2018}\u{2019}"
            + "（）《》【】…—～·"
            + "!?,.;:"
            + "\"'"
            + "()[]{}<>"
            + "-_/\\|"
            + "@#$%^&*+=~`"
            + "＄￥"
        for ch in chars {
            for sc in ch.unicodeScalars { s.insert(sc) }
        }
        return s
    }()

    // MARK: - 归一化

    static func clean(_ s: String) -> String {
        let nfkc = s.precomposedStringWithCompatibilityMapping
        var result = ""
        for sc in nfkc.unicodeScalars {
            if PUNCT.contains(sc) { continue }
            if shouldRemove(sc) { continue }
            result.unicodeScalars.append(sc)
        }
        return result.lowercased()
    }

    static func rawFlat(_ s: String) -> String {
        s.precomposedStringWithCompatibilityMapping.lowercased()
    }

    private static func shouldRemove(_ sc: Unicode.Scalar) -> Bool {
        let cat = sc.properties.generalCategory
        switch cat {
        case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation,
             .initialPunctuation, .finalPunctuation, .otherPunctuation:
            return true
        case .mathSymbol, .currencySymbol, .modifierSymbol, .otherSymbol:
            return true
        case .spaceSeparator, .lineSeparator, .paragraphSeparator:
            return true
        case .control, .format, .surrogate, .privateUse, .unassigned:
            return true
        default:
            return false
        }
    }

    // MARK: - 相似度

    private static func bigrams(_ s: String) -> Set<String> {
        let chars = Array(s)
        guard chars.count >= 2 else { return chars.isEmpty ? [] : [s] }
        var g = Set<String>()
        for i in 0..<(chars.count - 1) {
            g.insert(String(chars[i...i+1]))
        }
        return g
    }

    private static func dice(_ a: String, _ b: String) -> Double {
        let ga = bigrams(a), gb = bigrams(b)
        guard !ga.isEmpty, !gb.isEmpty else { return 0 }
        return 2.0 * Double(ga.intersection(gb).count) / Double(ga.count + gb.count)
    }

    private static func matchSeed(text: String, seed: String, th: Double, homophone: Bool = true) -> Bool {
        if seed.count >= 2 && text.contains(seed) { return true }
        // 无 pypinyin，跳过音节谐音（不足部分由 DANGER_VARIANTS 顶替）
        return dice(text, seed) >= th
    }

    /// 变体词所属的危险类别（仅用于给出可读的拦截原因）
    private static func dangerCategory(_ canon: String) -> String {
        for (cat, seeds) in DANGER_SEEDS where seeds.contains(canon) { return cat }
        return "变体"
    }

    // MARK: - 垃圾判据

    private static func junkReason(raw: String, text: String) -> String? {
        if text.isEmpty { return "空输入/纯符号" }
        if has(reURL, text) || has(reMail, text) { return "纯链接/邮箱" }
        if has(reFormula, text) { return "纯算式/纯数字" }
        if text.count >= 3 {
            let chars = Array(text)
            var counts = [Character: Int]()
            for c in chars { counts[c, default: 0] += 1 }
            let top = Double(counts.values.max() ?? 0) / Double(text.count)
            if top >= 0.6 { return "单字符重复" }
        }
        let flat = rawFlat(raw)
        let multiWord = reEnglishWord.numberOfMatches(in: flat, range: NSRange(flat.startIndex..., in: flat)) >= 2
        if !multiWord {
            if has(reAlphaOnly, text) && text.count >= 5 { return "无意义字母串" }
            if has(reAlnumOnly, text) && text.count >= 6 { return "无意义字母数字串" }
        }
        if text.count <= 2 && !INTENT.contains(where: { text.contains($0) }) { return "过短且无提问意图" }
        return nil
    }

    // MARK: - 主判定

    static func classify(_ raw: String) -> Verdict {
        let text = clean(raw)
        let flat = rawFlat(raw)

        // 1) 危险词表（直接匹配 + 相似度，无谐音）
        for (cat, seeds) in DANGER_SEEDS {
            for sd in seeds {
                if matchSeed(text: text, seed: sd, th: 0.35) {
                    return .danger(reason: "词表[\(cat)]「\(sd)」")
                }
            }
        }

        // 1a) 变体/谐音等价表（顶替 pypinyin 音节比对与繁转简）
        for (canon, variants) in DANGER_VARIANTS {
            for v in variants where text.contains(v) {
                return .danger(reason: "词表[\(dangerCategory(canon))]「\(v)」→「\(canon)」")
            }
        }

        // 1b) 危险句式
        for (pa, pb, name) in DANGER_STRUCT {
            if has(pa, text) && has(pb, text) {
                return .danger(reason: "结构规则：\(name)")
            }
        }
        for (pat, name) in DANGER_ZH_STRUCT {
            if has(pat, text) {
                return .danger(reason: "句式规则：\(name)")
            }
        }
        if has(DANGER_EN_PHRASE, flat) {
            return .danger(reason: "句式规则：english 求死短语")
        }
        for (pa, pb, name) in DANGER_EN_STRUCT {
            if has(pa, flat) && has(pb, flat) {
                return .danger(reason: "句式规则：\(name)")
            }
        }

        // 2) 垃圾/无意义
        if let jr = junkReason(raw: raw, text: text) {
            return .invalid(reason: "垃圾判据：\(jr)")
        }

        // 2b) 元请求
        for (pat, name) in META_PAT {
            if has(pat, text) {
                return .invalid(reason: "元请求：\(name)")
            }
        }
        if has(META_DEV_STRONG, text) {
            return .invalid(reason: "元请求：设备/账号操作请求")
        }
        if has(META_DEV_WEAK, text) && (has(DEV_OBJ, text) || !has(LIFE_OBJ, text)) {
            return .invalid(reason: "元请求：设备/账号操作请求")
        }

        // 2c) 域外请求（只认原字，不开谐音）
        for sd in OFFTOPIC_SEEDS {
            if matchSeed(text: text, seed: sd, th: 0.45, homophone: false) {
                return .invalid(reason: "域外请求「\(sd)」")
            }
        }

        let hasAnchor = ANCHOR.filter { text.contains($0) }
        let hasIntent = INTENT.filter { text.contains($0) }

        // 2d) 求助/选择问句（省略主语也算有效）
        if has(HELP_ASK, text) || has(CHOICE_ASK, text) {
            return .valid
        }

        // P3: 锚点必须同时有提问意图
        if !hasAnchor.isEmpty && !hasIntent.isEmpty {
            return .valid
        }

        // 隐含第一人称
        var weak = IMPLICIT.filter { text.contains($0) } + THING.filter { text.contains($0) }
        if has(IMP_VERB, text) { weak.append("想+动作") }
        if !weak.isEmpty && text.count >= 5 {
            return .valid
        }

        return .invalid(reason: "无处境锚点（未指明问的是谁/什么事）")
    }
}
