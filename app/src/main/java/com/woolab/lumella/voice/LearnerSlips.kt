package com.woolab.lumella.voice

/**
 * A slip in the learner's sentence that a rule can see: [said] is their word, [fixed] the same word
 * right. A recast says it right when it contains one of [right] ("매웠" or "매워" for "맵었어요");
 * [wrong], when set, is the slip itself showing up in the tutor's own words ("맵었").
 */
data class LearnerSlip(
    val said: String,
    val fixed: String,
    val right: List<String>,
    val wrong: String?,
    /** Why, in a few English words, for the instruction that names the slip. */
    val why: String,
)

/**
 * The two slips the voice keeps missing in a split reply's first part, found in the learner's
 * transcript by rule so the first part can be checked against them ([FirstPartGate]).
 *
 * Why these two: aaai27 split_reply_probe.py, script v6, six runs of each of five prompt versions
 * (2026-10-17). Every particle swap (이/가 where 을/를 belongs) was recast 6/6 by the prompt alone,
 * while "시장에 떡볶이를 먹었어요" stayed at 1/6 in every version and "맵었어요" moved between 0/6
 * and 5/6. Both are textbook learner errors:
 *
 * - ㅂ-irregular conjugated as regular: a stem of [IRREGULAR_B] directly followed by 아/어/았/었
 *   ("맵었어요" -> "매웠어요", "아름답았어요" -> "아름다웠어요"; 돕/곱 take 와: "돕았어요" -> "도왔어요").
 *   Only listed stems: 입다, 잡다, 좁다 and the like are regular, and "입었어요" is right.
 * - 에 for the place of an action: a word ending in one of [PLACES] + 에, in a sentence whose
 *   predicate is an action ([ACTIONS]): "시장에 떡볶이를 먹었어요" -> "시장에서". Not when the
 *   predicate is going, coming or being there ("시장에 갔어요", "집에 있어요"), not for a time
 *   ("토요일에 친구를 만났어요").
 *
 * Precision before recall: a slip found here is named to the voice as already checked, and a first
 * part without its fix is dropped and asked for again, so a false one makes the tutor "correct"
 * right Korean. Between the place and the predicate only an object, a companion or one of
 * [ADVERBS] may stand (and a bare noun right before 먹다/마시다: "시장에 떡볶이 먹었어"). Anything
 * else may open a clause in which 에 is right: "제주도에 가 봤어요", "집에 오면 숙제를 해요",
 * "한국에 대해 공부했어요", "시장에 있는 식당에서 먹었어요". An object that is a place itself is
 * where something ends up: "서울에 집을 샀어요" is right. Architect review 2026-10-17 traced
 * those sentences through the first version, which only blocked listed motion words and clause
 * endings, and every one was flagged. The second and third reviews found verb forms that looked
 * like the allowed words, now told apart: a companion's 와 follows a person ([WA_COMPANIONS]), so
 * "공원에 나와 운동을 했어요" and "도시락을 싸와" are not companions; a nominalised verb or a modifier
 * is not an object ("집에 오기를 기다렸어요", "방에 놓을 책상을 샀어요"); a connective, with a
 * particle or not, is not a bare noun ("집에 오자마자 먹었어요", "한국에 와서도 먹었어요"); a purpose
 * fragment is not a predicate ("도서관에 공부하러요", "공원에 산책하러갔어요"); an action ending in
 * -고 is that word, not a longer one starting with it ("회사에 보고했어요"); and an action noun is
 * one only with 하다 after it ("학교에 운동장이요").
 *
 * Anything else is the voice's own recast to make. The lists are read by aaai27
 * split_reply_probe.py, which runs the same rules on the same sentences.
 */
object LearnerSlips {
    fun find(transcript: String): List<LearnerSlip> {
        val found = mutableListOf<LearnerSlip>()
        for (sentence in transcript.split(SENTENCE_END)) {
            val words = sentence.replace(QUOTES, " ").split(' ', ',').filter { it.isNotBlank() }
            words.mapNotNullTo(found) { irregularB(it) }
            placeOfAction(words)?.let { found += it }
        }
        return found.distinctBy { it.said }
    }

    private fun irregularB(word: String): LearnerSlip? {
        val stem = STEMS_LONGEST_FIRST.firstOrNull { word.length > it.length && word.startsWith(it) } ?: return null
        val ending = word[stem.length]
        if (ending !in "아어았었") return null
        // The stem's last syllable without its ㅂ final: 맵 -> 매.
        val base = stem.dropLast(1) + (stem.last() - BIEUP_FINAL)
        val past = ending == '았' || ending == '었'
        val (now, then) = if (stem in WA_STEMS) "와" to "왔" else "워" to "웠"
        val dict = stem + "다"
        return LearnerSlip(
            said = word,
            fixed = base + (if (past) then else now) + word.substring(stem.length + 1),
            right = listOf(base + then, base + now),
            wrong = stem + ending,
            why = "$dict drops its ㅂ: $base${now}요, $base${then}어요",
        )
    }

    private fun placeOfAction(words: List<String>): LearnerSlip? {
        if (words.size < 2) return null
        var p = words.lastIndex
        // "먹고 싶어요", "먹고 있어요": the action is the word before the auxiliary.
        if (words[p].startsWith("싶") || (words[p].startsWith("있") && words[p - 1].endsWith("고"))) p--
        // The place nearest the predicate: "시장에 사람이 많았고 우리는 시장에 떡볶이 먹었어요".
        val at = (p - 1 downTo 0).firstOrNull { placeOf(words[it]) != null } ?: return null
        val place = placeOf(words[at]) ?: return null
        val predicate = words[p]
        val between = words.subList(at + 1, p)
        if (PURPOSE_MARKS.any { it in predicate }) return null
        val eats = EATING.any { predicate.startsWith(it) }
        val argumentsOnly = between.withIndex().all { (i, w) ->
            isArgument(w, between.getOrNull(i + 1)) || (eats && i == between.lastIndex && isBareNoun(w))
        }
        if (!argumentsOnly) return null
        val action = ACTIONS.any { isAction(predicate, it) } ||
            (predicate.removeSuffix("고") in BARE_HADA && between.any { w -> ACTION_NOUNS.any { w == it + "을" || w == it + "를" } })
        if (!action) return null
        val word = words[at]
        return LearnerSlip(
            said = word,
            fixed = word + "서",
            // "광장시장에" is said right as "시장에서" too.
            right = listOf(word + "서", place + "에서").distinct(),
            wrong = null,
            why = "the place where you do something takes 에서, not 에",
        )
    }

    /** The [PLACES] entry that [word] (a noun + 에) names, or null. */
    private fun placeOf(word: String): String? {
        if (!word.endsWith("에")) return null
        val noun = word.dropLast(1)
        return PLACES.firstOrNull { if (it.length == 1) noun == it else noun.endsWith(it) }
    }

    /**
     * An object (not itself a place), a companion ("친구랑", "가족과") or one of [ADVERBS]. [next] is
     * the word after it: an object followed by another is a modifier ("놓을 책상을").
     */
    private fun isArgument(word: String, next: String?): Boolean = when {
        word in ADVERBS -> true
        word.length < 2 -> false
        word.endsWith("을") || word.endsWith("를") -> {
            val noun = word.dropLast(1)
            !isPlaceObject(noun) && placeOf(noun + "에") == null &&
                (!noun.endsWith("기") || noun in GI_NOUNS) &&
                !(next != null && (next.endsWith("을") || next.endsWith("를")))
        }
        word.endsWith("와") -> named(word.dropLast(1), WA_COMPANIONS)
        else -> COMPANIONS.any { word.endsWith(it) }
    }

    /** [noun] is one of [names], or ends in one of two syllables or more ("남자친구" ends in "친구"). */
    private fun named(noun: String, names: List<String>): Boolean =
        names.any { if (it.length == 1) noun == it else noun.endsWith(it) }

    private fun isPlaceObject(noun: String): Boolean = named(noun, PLACE_OBJECTS)

    /**
     * A noun without a case particle that does not end like a verb form ("떡볶이", "커피", "밥"),
     * one of [PARTICLES] allowed: "밥도", "피자만" are bare nouns, "와서도", "가서만" are not.
     */
    private fun isBareNoun(word: String): Boolean {
        val stem = PARTICLES.firstOrNull { word.length > it.length && word.endsWith(it) }?.let { word.dropLast(it.length) } ?: word
        return stem.last() !in VERB_LIKE_ENDS && VERB_LIKE_SUFFIXES.none { stem.endsWith(it) }
    }

    /**
     * An -고 entry is the whole word ("보고", "보고요"), not one it starts ("보고했어요"); an action noun
     * is followed by 하다 ("운동했어요", not "운동장이요").
     */
    private fun isAction(predicate: String, action: String): Boolean = when {
        action.endsWith("고") -> predicate == action || predicate == action + "요"
        action in ACTION_NOUNS -> predicate.startsWith(action) && predicate.getOrNull(action.length)?.let { it in HADA } == true
        else -> predicate.startsWith(action)
    }

    /** Index of ㅂ among the 28 finals of a Hangul syllable. */
    private const val BIEUP_FINAL = 17

    private val SENTENCE_END = Regex("[.!?？…~]+")
    private val QUOTES = Regex("[\"'“”‘’()]")

    /** Stems that drop ㅂ before 아/어 (dictionary form minus 다). */
    val IRREGULAR_B = listOf(
        "덥", "춥", "맵", "어렵", "쉽", "귀엽", "무겁", "가볍", "뜨겁", "차갑", "아름답", "즐겁", "반갑",
        "고맙", "시끄럽", "더럽", "싱겁", "부럽", "무섭", "아깝", "가깝", "두껍", "부드럽", "간지럽",
        "괴롭", "외롭", "새롭", "날카롭", "지겹", "눕", "줍", "밉", "그립", "놀랍", "미끄럽",
        "자연스럽", "사랑스럽", "부담스럽", "걱정스럽", "만족스럽", "돕", "곱",
    )

    /** The two that become 와 rather than 워. */
    val WA_STEMS = listOf("돕", "곱")

    private val STEMS_LONGEST_FIRST = IRREGULAR_B.sortedByDescending { it.length }

    /** Places; a one-syllable place must be the whole word ("집에"), a longer one may end it ("광장시장에"). */
    val PLACES = listOf(
        "시장", "식당", "도서관", "공원", "학교", "학원", "회사", "카페", "커피숍", "백화점", "마트", "슈퍼",
        "편의점", "영화관", "극장", "바다", "해변", "호수", "거리", "가게", "교실", "부엌", "거실", "공항",
        "호텔", "박물관", "미술관", "체육관", "수영장", "운동장", "놀이공원", "병원", "은행", "우체국",
        "서점", "빵집", "시내", "동네", "한강", "명동", "홍대", "강남", "남산", "제주도", "부산", "서울",
        "한국", "미국", "일본", "중국", "고향", "노래방", "피시방", "찜질방", "기숙사", "사무실", "교회",
        "시골", "캠핑장", "축제", "콘서트", "파티", "기차", "버스", "지하철", "비행기", "집", "방", "산", "강",
    )

    /**
     * Predicates that are actions done at a place (word starts; an -고 entry is the whole word). Not
     * 쓰다 ("고향에 편지를 썼어요" sends it there) or 이야기하다 ("회사에 얘기했어요" tells the
     * company): 에 is right there. Not 삽니다, which is 살다 too: "서울에 삽니다" is right.
     */
    val ACTIONS = listOf(
        "먹", "마시", "마셨", "마셔", "공부", "운동", "쇼핑", "요리", "산책", "수영", "노래", "구경",
        "데이트", "식사", "아르바이트", "알바", "연습", "청소", "빨래", "게임", "숙제", "일했", "일해",
        "일하", "일합", "놀았", "놀아", "놉니", "놀고", "만났", "만나요", "만납", "만나고", "샀", "사요",
        "사고", "봤", "봐요", "보았", "봅니", "보고", "읽", "배웠",
        "배워", "배웁", "배우고", "가르쳤", "가르쳐", "가르치", "찍었", "찍어", "찍습", "찍고", "기다렸",
        "기다려", "기다립", "기다리고", "쉬었", "쉬어", "쉽니", "쉬고", "잤", "자요", "잡니", "자고",
        "들었", "듣고", "췄", "춰", "추고",
    )

    /**
     * A purpose clause, not a predicate: "도서관에 공부하러요" (going there to study), and the same
     * with its verb run in by the transcript ("공원에 산책하러갔어요").
     */
    val PURPOSE_MARKS = listOf("러", "려고")

    /** The [ACTIONS] a bare noun may stand right before: "시장에 떡볶이 먹었어". */
    val EATING = listOf("먹", "마시", "마셨", "마셔")

    /** 하다 alone, an action only with an action noun as its object ("숙제를 했어요"). */
    val BARE_HADA = listOf("했어요", "해요", "했습니다", "합니다", "했어", "해", "하")

    val ACTION_NOUNS = listOf(
        "공부", "운동", "쇼핑", "요리", "산책", "수영", "노래", "구경", "데이트", "식사", "아르바이트",
        "알바", "연습", "청소", "빨래", "숙제", "일", "게임",
    )

    /** Words that may stand between the place and the predicate as they are. */
    val ADVERBS = listOf(
        "같이", "함께", "혼자", "많이", "조금", "좀", "또", "다시", "자주", "매일", "맛있게", "재미있게",
        "열심히", "너무", "정말", "진짜", "아주", "처음", "먼저", "어제", "오늘", "다",
    )

    /** A companion's particle after any noun ("친구랑", "가족과"); 와 only after [WA_COMPANIONS]. */
    val COMPANIONS = listOf("랑", "과")

    /**
     * Who 와 may follow as a companion ("친구와", "엄마와", "저와"); any other -와 is 오다 after a
     * verb ("나와", "돌아와", "싸와", "가지고와"). Not 나: "나와" is far more often "come out".
     */
    val WA_COMPANIONS = listOf(
        "친구", "엄마", "아빠", "언니", "누나", "오빠", "아내", "동료", "할머니", "할아버지", "어머니", "아버지",
        "아이", "이모", "고모", "애인", "저", "너", "우리",
    )

    /** Particles a bare noun may carry before 먹다/마시다 ("밥도 먹었어요"). */
    val PARTICLES = listOf("부터", "까지", "라도", "조차", "마저", "도", "만")

    /** The syllable after an action noun when it is 하다 ("공부했어요", "운동하고", "수영합니다"). */
    const val HADA = "하해했합할한"

    /**
     * Objects with which 에 is right, besides [PLACES], matched as places are ("단독주택" ends in
     * "주택"): places themselves ("강남에 아파트를 샀어요", it is there), what is sat for an entry
     * ("회사에 면접을 봤어요", applied to it) and what is taken out with a bank ("은행에 적금을 들었어요").
     */
    val PLACE_OBJECTS = listOf(
        "아파트", "땅", "건물", "빌딩", "별장", "주택", "원룸", "오피스텔", "빌라", "상가", "펜션", "한옥", "밭", "논",
        "농장", "공장", "토지", "면접", "시험", "적금", "예금", "보험",
    )

    /** Nouns ending in 기 that are objects; any other -기 is a verb made a noun ("집에 오기를 기다렸어요"). */
    val GI_NOUNS = listOf("고기", "아기", "경기")

    /**
     * Last syllables of verb forms (가 봤어요, 오면, 대해, 있는, 가기, 들어가서, 먹고, 때, 오니까, 와서야,
     * 누워, 가거든): not a bare noun.
     */
    const val VERB_LIKE_ENDS = "가와서고면는은을기게지며데러려해아어여때대니다요까야워켜쳐겨셔꿔둬줘봐든"

    /** Longer verb endings whose last syllable a noun may end in too ("오자마자", but "피자"). */
    val VERB_LIKE_SUFFIXES = listOf("자마자")
}
