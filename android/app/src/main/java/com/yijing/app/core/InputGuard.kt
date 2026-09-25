package com.yijing.app.core

import java.text.Normalizer
import java.util.Locale

/**
 * 输入有效性拦截 — P3 版本。
 *
 * ⚠️ 本文件与 iOS 侧 `ios/Yijing/Services/InputGuard.swift` 是**同源同表**的实现：
 * 词表、正则、判定顺序、判据阈值必须逐条保持一致，改任何一侧都要同步改另一侧，
 * 并用同一批语料（A/B/C/D/E 五批，382 条）做等价性回归，要求 0 差异。
 *
 * 逻辑：基于表面规则，不接语义。非法输入软引导（P_INVALID 前置到系统提示词），
 * 危险输入硬拦截（直接给 DANGER_REPLY，不调用模型）。
 * P3 变更：锚点（ANCHOR）必须同时有提问意图（INTENT）；INTENT 补充日常问法。
 *
 * 与 Python 原型（input_guard_sim.py）的两处已知差异，均已用等价表补上：
 *   1) 无 pypinyin，做不了音节谐音比对 → 用「变体/谐音」表顶替（自沙/读品/为造…）
 *   2) 不做繁转简 → 用「繁体写法」表顶替（两处表格同源，见 DANGER_VARIANTS）
 *
 * 另外，Java 正则默认是 ASCII 语义而 Swift(ICU)/Python 是 Unicode 语义，
 * 这里凡涉及 `\w` / `\b` / `\d` 的地方都显式写成 Unicode 字符类，避免两端判定漂移。
 */
object InputGuard {

    /** 判定结果。valid 放行；invalid 走软引导；danger 直接安全提示，不进模型。 */
    sealed class Verdict {
        object Valid : Verdict()
        data class Invalid(val reason: String) : Verdict()
        data class Danger(val reason: String) : Verdict()
    }

    // ---------------------------------------------------------------- 用户可见文案

    const val DANGER_REPLY = "这个问题我没办法帮你。\n\n如果此刻你或身边的人正处于危险中，请立即联系当地紧急服务；情绪困扰可拨打 12356 心理援助热线。"

    const val P_INVALID =
        "【非有效提问·软引导】开场白固定为：「好像不太明白你的问题呢，但是根据你得到的卦象，当前建议你……」随后照常按卦象→象意→建议三段输出；不追问用户、不解读用户没说的信息。"

    // ---------------------------------------------------------------- 种子表

    private val DANGER_SEEDS: List<Pair<String, List<String>>> = listOf(
        "自我伤害" to listOf("自杀", "自残", "轻生", "不想活", "结束生命", "一了百了", "了断", "活不下去"),
        "暴力伤害" to listOf("杀人", "打伤", "报复社会", "伤害他人", "行凶"),
        "武器爆炸" to listOf("炸弹", "爆炸物", "枪支", "管制刀具"),
        "毒品" to listOf("毒品", "吸毒", "违禁药物"),
        "赌博" to listOf("赌博", "赌场", "博彩网站"),
        "诈骗违法" to listOf("诈骗", "伪造", "骗过", "洗钱"),
        "色情" to listOf("色情", "裸聊", "黄色网站"),
        "极端破坏" to listOf("毁灭世界", "世界毁灭", "人类毁灭"),
    )

    /**
     * 变体/谐音等价表 —— 顶替 Python 原型的 pypinyin 音节比对与 OpenCC 繁转简。
     * key 为 DANGER_SEEDS 里的规范词，value 为常见同音错写与繁体写法；
     * 命中 value 即等价于命中 key（只用于判定，不改写原文）。
     */
    private val DANGER_VARIANTS: List<Pair<String, List<String>>> = listOf(
        "自杀" to listOf("自沙", "自殺", "自刹", "自煞", "滋杀", "自纱"),
        "自残" to listOf("自殘", "自惨", "自慘"),
        "轻生" to listOf("輕生", "青生"),
        "一了百了" to listOf("一瞭百瞭"),
        "了断" to listOf("了斷"),
        "杀人" to listOf("殺人", "沙人"),
        "打伤" to listOf("打傷"),
        "报复社会" to listOf("報復社會", "抱负社会"),
        "伤害他人" to listOf("傷害他人"),
        "行凶" to listOf("行兇"),
        "炸弹" to listOf("炸彈", "咋弹"),
        "爆炸物" to listOf("爆炸物品"),
        "枪支" to listOf("槍支", "枪枝"),
        "毒品" to listOf("读品", "度品", "讀品", "独品"),
        "违禁药物" to listOf("違禁藥物"),
        "赌博" to listOf("賭博", "赌搏", "赌赙"),
        "赌场" to listOf("賭場"),
        "博彩网站" to listOf("博彩網站"),
        "诈骗" to listOf("詐騙", "咋骗"),
        "伪造" to listOf("为造", "偽造", "為造", "韦造", "伪照"),
        "洗钱" to listOf("洗錢"),
        "黄色网站" to listOf("黃色網站"),
        "裸聊" to listOf("裸聊视频"),
        "毁灭世界" to listOf("毀滅世界", "毁灭世界吧"),
        "世界毁灭" to listOf("世界毀滅"),
        "人类毁灭" to listOf("人類毀滅"),
    )

    private val OFFTOPIC_SEEDS =
        listOf("写诗", "首诗", "笑话", "翻译", "天气", "唱歌", "画画", "写代码", "数学题")

    private val ANCHOR: List<String> = listOf(
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
    )

    // P3: 补充日常问法
    private val INTENT: List<String> = listOf(
        "吗", "呢", "？", "?", "怎么", "如何", "能否", "能不能", "该不该", "是否",
        "要不要", "多久", "什么时候", "哪", "什么", "为什么", "多少", "会不会",
        "可不可以", "求", "帮", "看看", "算", "占", "卜", "签", "指点",
        "怎么样", "怎样", "咋办", "怎么办", "如何是好", "能行不", "行吗",
        "好不好", "吉凶", "顺不顺", "成不成", "可不可"
    )

    private val IMPLICIT = listOf(
        "打算", "准备", "计划", "想要", "想问问", "要不要", "该不该", "合适吗",
        "靠谱吗", "可行吗", "能过吗", "能成吗", "可以吗", "行不行", "会不会"
    )

    private val THING = listOf(
        "创业", "升职", "跳槽", "驾照", "网店", "城市", "失眠", "睡眠", "姻缘", "桃花",
        "转行", "副业", "存款", "买房", "结婚", "考试", "面试", "投资", "搬家",
        // 出行/聚会类事情：作为「隐含第一人称」的弱证据（需 ≥5 个码点）
        // 注：不收「机票/车票/酒店/民宿」—— 它们是可订的物品而非处境，
        // 收了会把「帮我订张机票」「酒店怎么订便宜」这类请求误放行。
        "出去玩", "出去耍", "出去逛", "旅行", "旅游", "出游", "度假", "出行",
        "自驾", "露营", "爬山", "聚会", "聚餐", "团建", "送礼", "回老家", "走亲戚"
    )

    // ---------------------------------------------------------------- 正则

    private fun rx(pattern: String, vararg options: RegexOption): Regex = Regex(pattern, options.toSet())

    private fun has(re: Regex, text: String): Boolean = re.containsMatchIn(text)

    /**
     * Swift(ICU) 与 Python 的 `\w` 都是 Unicode 语义，Java 默认只认 ASCII。
     * 这里显式展开成等价字符类：字母 / 标记 / 十进制数字 / 连接符标点 / 连接控制符。
     */
    private const val WB = "[\\p{L}\\p{M}\\p{Nd}\\p{Pc}\\u200C\\u200D]"

    // 垃圾判据
    private val reURL = rx("""^(https?://|www\.)\S+${'$'}""", RegexOption.IGNORE_CASE)
    private val reMail = rx("^[${WB}.\\-]+@[${WB}\\-]+\\.[a-z]{2,}${'$'}", RegexOption.IGNORE_CASE)
    private val reFormula = rx("""^[\d\s\+\-\*/=×÷\(\)\.\^]+${'$'}""")
    private val reAlphaOnly = rx("""^[a-z]+${'$'}""")
    private val reAlnumOnly = rx("""^[a-z0-9]+${'$'}""")
    private val reEnglishWord = rx("""[A-Za-z]{2,}""")

    // 危险结构规则
    private val DANGER_STRUCT: List<Triple<Regex, Regex, String>> = listOf(
        Triple(
            rx("""(枪|枪支|弹药|炸药|炸弹|毒品|违禁药|管制刀具|冰毒|麻古)"""),
            rx("""(哪|怎么|如何|搞|弄|买|卖|做|制|造|获取|渠道|推荐|介绍|教我|帮我)"""),
            "违禁物+获取动词"
        ),
        Triple(
            rx("""赌"""),
            rx("""(球|局|场|博|平台|网站|彩票|下注|押注|安全|靠谱)"""),
            "赌博场景词"
        ),
        Triple(
            rx("""报复"""),
            rx("""(社会|人类|同学|同事|别人|全班|学校|他们)"""),
            "报复对象"
        ),
    )

    private val DANGER_ZH_STRUCT: List<Pair<Regex, String>> = listOf(
        rx("""(我|自己|本人)[^，。]{0,4}(死|不想活|活不下去|活够了|消失|跳楼|割腕|上吊|轻生)(?!一段时间|一会|一阵|一下|几天|这条心)""") to "第一人称+求死",
        rx("""(想死|去死|寻死|求死|一死|死掉算了)""") to "求死词",
        rx("""(还不如|不如|干脆|直接|大不了)[^，。]{0,4}(死|了断|一了百了)""") to "不如死了",
        rx("""(死|走)了[^，。]{0,4}(算了|利索|干净|轻松|就解脱)""") to "死了算了",
        rx("""(看不到|看不见|没有|没啥|毫无|没任何)[^，。]{0,4}(希望|意义|意思|出路)""") to "绝望",
        rx("""(活着|活下去|继续活|活着下)[^，。]{0,4}(没意思|没意义|太累|太苦|没劲|受罪)""") to "活着没意思",
        rx("""(不想|不愿|懒得)[^，。]{0,3}(活|醒|存在|睁眼)""") to "不想活",
        rx("""(跳楼|割腕|上吊|自缢|服毒|烧炭|投河)""") to "自杀方式",
        rx("""(没人|没有人|谁也)[^，。]{0,4}(帮|理解|在乎|需要我|在乎我)""") to "无援绝望",
        rx("""(痛苦|难受|折磨)[^，。]{0,4}(结束|停止|解脱)""") to "求结束痛苦",
        rx("""^(姐姐|哥哥|妈妈|爸爸|老师|医生|有人|小哥哥|小姐姐)[，,]?[^。]{0,6}(我|自己)[^，。]{0,6}(难受|痛苦|崩溃|撑不住|受不了|绝望|想哭)""") to "呼救式情绪",
    )

    private val DANGER_EN_PHRASE = rx(
        """(kill myself|killing myself|end my life|end it all|suicid|wish i (was|were)n'?t here|better off (dead|without me)|can'?t do this anymore|don'?t want to (live|be here|exist)|no reason to live|want the pain to stop|what'?s the point|blow your face off|i want to do this|hang myself|noose)""",
        RegexOption.IGNORE_CASE
    )

    private val DANGER_EN_STRUCT: List<Triple<Regex, Regex, String>> = listOf(
        Triple(
            rx("(?<!${WB})(die|dead|death|kill(?!\\s*(time|some time))|jump off|overdose|noose)(?!${WB})", RegexOption.IGNORE_CASE),
            rx("""(want|wish|hope|rather|ready|going to|gonna|plan|why|point|can'?t|stop|end|tired|nothing)""", RegexOption.IGNORE_CASE),
            "english 求死结构"
        ),
    )

    // 元请求
    private val META_PAT: List<Pair<Regex, String>> = listOf(
        rx("""(帮我写|帮我翻|写点东西|写一篇|写首|写个|做个|讲个|编个|画个|生成一篇)""") to "代写/生成请求",
        rx("""你的(名字|声音|年龄|性别|父亲|妈妈|照片|微信|电话)""") to "身份打听",
        rx("""(你是(谁|啥|who|什么|chatgpt|人吗)|我叫)""") to "身份打听/自我介绍",
        rx("""(免费看|免费给看|无偿|案例积累|积累案例|需反馈|接单|收费|牌阵|看盘|可问|不要太模糊|私信|加微)""") to "从业者帖/广告",
        rx("""(很差|垃圾|骗子|忽悠|坑钱|踩雷|就是假的)""") to "吐槽/差评",
        rx("""(不能|没法|为什么|为啥)[^，。]{0,8}(发图片|上传|提现|到账|登录|扣费|退款)""") to "客服投诉",
    )

    private val META_DEV_STRONG = rx("""(验证码|客服|闪退|发图片|上传|播放|演示|弹窗|扣费|提现|到账)""")
    private val META_DEV_WEAK = rx("""(打开|新建|关闭|下载|安装|卸载|注销|充值|充钱|退款|登录|注册)""")
    private val DEV_OBJ = rx("""(电脑|桌面|手机|网站|app|应用|软件|图片|文件夹|视频|文件|账号|会员|金币|功能|设置|页面|浏览器)""")
    private val LIFE_OBJ = rx("""(网店|店铺|店|公司|生意|厂|项目|合同|股|工资|房子|车)""")

    // 求助/选择
    private val HELP_ASK = rx("""(求助|有会看的|哪位|有人会|请教|大佬|什么意思|指点一下|帮忙看|帮看|帮我解|谁能帮)""")
    private val CHOICE_ASK = rx("""(.)(?:还是)?不\1""")
    private val IMP_VERB = rx("""想[^，。！？]{0,3}(考|学|换|搬|辞|离|结|生|做|开|创|投|买|卖|借|还|问|算|签|申请|报名|转|跳)""")

    // 标点（与 shouldRemove 的 Unicode 分类判据等价，此处显式列出以便阅读）
    private val PUNCT: Set<Int> = run {
        // 注意：全角/弯引号一律写成 \uXXXX，不要写字面引号，否则会把字符串提前截断
        val chars = " \t\r\n" +
            "\uFF0C\u3002\uFF01\uFF1F\u3001\uFF1B\uFF1A" +
            "\u201C\u201D\u2018\u2019" +
            "\uFF08\uFF09\u300A\u300B\u3010\u3011\u2026\u2014\uFF5E\u00B7" +
            "!?,.;:" +
            "\"'" +
            "()[]{}<>" +
            "-_/\\|" +
            "@#\$%^&*+=~`" +
            "\uFF04\uFFE5"
        val s = HashSet<Int>()
        var i = 0
        while (i < chars.length) {
            val cp = chars.codePointAt(i)
            i += Character.charCount(cp)
            s.add(cp)
        }
        s
    }

    // ---------------------------------------------------------------- 归一化

    /** NFKC 兼容归一 + 去标点/符号/空白/控制符 + 小写。 */
    fun clean(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            i += Character.charCount(cp)
            if (PUNCT.contains(cp)) continue
            if (shouldRemove(cp)) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    /** 只做 NFKC + 小写，保留标点（英文句式规则用原始形态匹配）。 */
    fun rawFlat(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun shouldRemove(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt(),
        Character.SPACE_SEPARATOR.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.SURROGATE.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt() -> true

        else -> false
    }

    // ---------------------------------------------------------------- 相似度

    private fun codePoints(s: String): IntArray = s.codePoints().toArray()

    private fun bigrams(s: String): MutableSet<String> {
        val cps = codePoints(s)
        if (cps.size < 2) return if (cps.isEmpty()) HashSet() else hashSetOf(s)
        val g = HashSet<String>()
        for (i in 0 until cps.size - 1) {
            g.add(String(cps, i, 2))
        }
        return g
    }

    private fun dice(a: String, b: String): Double {
        val ga = bigrams(a)
        val gb = bigrams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        var inter = 0
        for (x in ga) if (gb.contains(x)) inter++
        return 2.0 * inter.toDouble() / (ga.size + gb.size).toDouble()
    }

    private fun matchSeed(text: String, seed: String, th: Double, homophone: Boolean = true): Boolean {
        if (seed.length >= 2 && text.contains(seed)) return true
        // 无 pypinyin，跳过音节谐音（不足部分由 DANGER_VARIANTS 顶替）
        return dice(text, seed) >= th
    }

    /** 变体词所属的危险类别（仅用于给出可读的拦截原因） */
    private fun dangerCategory(canon: String): String {
        for ((cat, seeds) in DANGER_SEEDS) if (seeds.contains(canon)) return cat
        return "变体"
    }

    // ---------------------------------------------------------------- 垃圾判据

    private fun junkReason(raw: String, text: String): String? {
        if (text.isEmpty()) return "空输入/纯符号"
        if (has(reURL, text) || has(reMail, text)) return "纯链接/邮箱"
        if (has(reFormula, text)) return "纯算式/纯数字"
        val n = text.codePointCount(0, text.length)
        if (n >= 3) {
            val counts = HashMap<Int, Int>()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                counts[cp] = (counts[cp] ?: 0) + 1
            }
            val top = (counts.values.maxOrNull() ?: 0).toDouble() / n.toDouble()
            if (top >= 0.6) return "单字符重复"
        }
        val flat = rawFlat(raw)
        val multiWord = reEnglishWord.findAll(flat).count() >= 2
        if (!multiWord) {
            if (has(reAlphaOnly, text) && n >= 5) return "无意义字母串"
            if (has(reAlnumOnly, text) && n >= 6) return "无意义字母数字串"
        }
        if (n <= 2 && INTENT.none { text.contains(it) }) return "过短且无提问意图"
        return null
    }

    // ---------------------------------------------------------------- 主判定

    fun classify(raw: String): Verdict {
        val text = clean(raw)
        val flat = rawFlat(raw)

        // 1) 危险词表（直接匹配 + 相似度，无谐音）
        for ((cat, seeds) in DANGER_SEEDS) {
            for (sd in seeds) {
                if (matchSeed(text, sd, 0.35)) {
                    return Verdict.Danger("词表[$cat]「$sd」")
                }
            }
        }

        // 1a) 变体/谐音等价表（顶替 pypinyin 音节比对与繁转简）
        for ((canon, variants) in DANGER_VARIANTS) {
            for (v in variants) if (text.contains(v)) {
                return Verdict.Danger("词表[${dangerCategory(canon)}]「$v」→「$canon」")
            }
        }

        // 1b) 危险句式
        for ((pa, pb, name) in DANGER_STRUCT) {
            if (has(pa, text) && has(pb, text)) {
                return Verdict.Danger("结构规则：$name")
            }
        }
        for ((pat, name) in DANGER_ZH_STRUCT) {
            if (has(pat, text)) {
                return Verdict.Danger("句式规则：$name")
            }
        }
        if (has(DANGER_EN_PHRASE, flat)) {
            return Verdict.Danger("句式规则：english 求死短语")
        }
        for ((pa, pb, name) in DANGER_EN_STRUCT) {
            if (has(pa, flat) && has(pb, flat)) {
                return Verdict.Danger("句式规则：$name")
            }
        }

        // 2) 垃圾/无意义
        val jr = junkReason(raw, text)
        if (jr != null) {
            return Verdict.Invalid("垃圾判据：$jr")
        }

        // 2b) 元请求
        for ((pat, name) in META_PAT) {
            if (has(pat, text)) {
                return Verdict.Invalid("元请求：$name")
            }
        }
        if (has(META_DEV_STRONG, text)) {
            return Verdict.Invalid("元请求：设备/账号操作请求")
        }
        if (has(META_DEV_WEAK, text) && (has(DEV_OBJ, text) || !has(LIFE_OBJ, text))) {
            return Verdict.Invalid("元请求：设备/账号操作请求")
        }

        // 2c) 域外请求（只认原字，不开谐音）
        for (sd in OFFTOPIC_SEEDS) {
            if (matchSeed(text, sd, 0.45, homophone = false)) {
                return Verdict.Invalid("域外请求「$sd」")
            }
        }

        val hasAnchor = ANCHOR.filter { text.contains(it) }
        val hasIntent = INTENT.filter { text.contains(it) }

        // 2d) 求助/选择问句（省略主语也算有效）
        if (has(HELP_ASK, text) || has(CHOICE_ASK, text)) {
            return Verdict.Valid
        }

        // P3: 锚点必须同时有提问意图
        if (hasAnchor.isNotEmpty() && hasIntent.isNotEmpty()) {
            return Verdict.Valid
        }

        // 隐含第一人称
        val weak = (IMPLICIT.filter { text.contains(it) } + THING.filter { text.contains(it) }).toMutableList()
        if (has(IMP_VERB, text)) weak.add("想+动作")
        if (weak.isNotEmpty() && text.codePointCount(0, text.length) >= 5) {
            return Verdict.Valid
        }

        return Verdict.Invalid("无处境锚点（未指明问的是谁/什么事）")
    }
}
