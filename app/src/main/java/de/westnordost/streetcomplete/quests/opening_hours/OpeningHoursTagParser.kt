package de.westnordost.streetcomplete.quests.opening_hours

import ch.poole.openinghoursparser.*
import de.westnordost.streetcomplete.quests.opening_hours.adapter.OpeningMonthsRow
import de.westnordost.streetcomplete.quests.opening_hours.adapter.OpeningWeekdaysRow
import de.westnordost.streetcomplete.quests.opening_hours.model.CircularSection
import de.westnordost.streetcomplete.quests.opening_hours.model.TimeRange
import de.westnordost.streetcomplete.quests.opening_hours.model.Weekdays
import java.io.ByteArrayInputStream

object OpeningHoursTagParser {
    // returns null for values that are invalid or not representable in
    // StreetComplete opening hours edit widget
    // otherwise returns data structure that can be directly used to
    // initialize this editing widget
    fun parse(openingHours: String): List<OpeningMonthsRow>? {
        val rules: ArrayList<Rule>
        try {
            val input = ByteArrayInputStream(openingHours.toByteArray())
            val parser = OpeningHoursParser(input)
            rules = parser.rules(false)
        } catch (e: ParseException) {
            // parsing failed, value is malformed
            return null
        }
        if(!isRulesetToStreetCompleteSupported(rules)) {
            // parsable, not handled by StreetComplete
            return null
        }
        return transformStreetCompleteCompatibleRulesetIntoInternalForm(rules)
    }

    private fun transformStreetCompleteCompatibleRulesetIntoInternalForm(rules: ArrayList<Rule>): List<OpeningMonthsRow>? {
        var data = mutableListOf(OpeningMonthsRow())
        for (rule in rules) {
            if(rule.dates != null) {
                // month based rules, so we need month-limited rows that will be added later
                data = mutableListOf()
            }
        }

        for (rule in rules) {
            var index = 0
            if(rule.dates != null) {
                Assert.assert(rule.dates.size == 1)
                val start = rule.dates[0].startDate
                val end = rule.dates[0].endDate ?: start
                index = getIndexOfOurMonthsRow(data, start.month.ordinal, end.month.ordinal)
                if(index == -1) {
                    data.add(OpeningMonthsRow(CircularSection(start.month.ordinal, end.month.ordinal)))
                    index = data.size - 1
                }
            }
            for(time in rule.times){
                val dayData = daysWhenRuleApplies(rule)
                data[index].weekdaysList.add(OpeningWeekdaysRow(Weekdays(dayData), TimeRange(time.start, time.end)))
            }
        }

        return data
    }

    fun getIndexOfOurMonthsRow(monthRows: List<OpeningMonthsRow>, startMonth: Int, endMonth: Int): Int {
        var index = 0
        for(row in monthRows) {
            if(row.months.start == startMonth) {
                if(row.months.start == endMonth) {
                    return index
                }
            }
            index++
        }
        return -1
    }

    //returns array that can be used to initialize OpeningWeekdaysRow
    fun daysWhenRuleApplies(rule: Rule): BooleanArray {
        val dayData = BooleanArray(8) {false}
        Assert.assert(rule.holidays != null || rule.days.size >= 0)
        if(rule.days != null) {
            Assert.assert(rule.days.size == 1)
            val startDay = rule.days[0].startDay
            val endDay = rule.days[0].endDay ?: startDay // endDay will be null for single day ranges
            if(startDay <= endDay) {
                // ranges like Tuesday-Saturday
                for(day in WeekDay.values()) {
                    if(day >= startDay) {
                        if(day <= endDay) {
                            dayData[day.ordinal] = true
                        }
                    }
                }
            } else {
                // ranges like Saturday-Tuesday
                for (day in WeekDay.values()) {
                    if (day <= endDay || day >= startDay) {
                        dayData[day.ordinal] = true
                    }
                }
            }
        }
        if(rule.holidays != null) {
            Assert.assert(rule.holidays.size == 1)
            Assert.assert(rule.holidays[0].type == Holiday.Type.PH)
            Assert.assert(rule.holidays[0].offset == 0)
            Assert.assert(rule.holidays[0].useAsWeekDay == true)
            dayData[7] = true
        }
        return dayData
    }
    // Returns true iff supported by StreetComplete
    // Returns false otherwise, in cases where it is not directly representable
    fun isRulesetToStreetCompleteSupported(ruleset: ArrayList<Rule>): Boolean {
        for (rule in ruleset) {
            if(reduceRuleToStreetCompleteSupported(rule) == null) {
                return false
            }
        }
        if(areOnlySomeRulesMonthBased(ruleset)) {
            // StreetComplete can handle month based rules, but requires all of them to be month based
            return false
        }
        return true
    }

    fun areOnlySomeRulesMonthBased(ruleset: ArrayList<Rule>): Boolean {
        var rulesWithMonthLimits = 0
        for (rule in ruleset) {
            if(rule.dates != null) {
                rulesWithMonthLimits += 1
            }
        }
        if(rulesWithMonthLimits == 0) {
            return false
        }
        if (rulesWithMonthLimits == ruleset.size) {
            return false
        }
        return true
    }


    // Reduces rule to a subset supported by StreetComplete
    // in case of any info that would be lost it returns null
    // null is also returned in cases where conversion would be necessary
    // and there is any risk of loss of any data
    fun reduceRuleToStreetCompleteSupported(rule: Rule): Rule? { // following are ignored:
        val returned = emptyRule()
        if(rule.days == null && rule.holidays == null) {
            // SC requires explicit specification of days of a week or PH
            // holidays may contain some other holidays, but such cases will
            // fail a holiday-specific check
            return null
        }
        if(rule.days != null) {
            val simplifiedWeekDayRanges: MutableList<WeekDayRange> = ArrayList()
            for (weekDayRange in rule.days) {
                val simplifiedDateRange = reduceWeekDayRangeToSimpleDays(weekDayRange) ?: return null
                simplifiedWeekDayRanges.add(simplifiedDateRange)
            }
            if(simplifiedWeekDayRanges.size > 1){
                return null // TODO - how this may happen? Is it representable in SC?
            }
            returned.days = simplifiedWeekDayRanges // copy days of the week from the input rule
        }
        if (rule.dates != null) {
            val simplifiedDateRanges: MutableList<DateRange> = ArrayList()
            for (dateRange in rule.dates) {
                val simplifiedDateRange = reduceDateRangeToFullMonths(dateRange) ?: return null
                simplifiedDateRanges.add(simplifiedDateRange)
            }
            if(simplifiedDateRanges.size > 1){
                // happens with rules such as `Mo-Fr 7:30-18:00, Sa-Su 9:00-18:00`
                // that are intentionally rejected as are not directly representable in SC
                // and handling them may result in unexpected silent transformation
                // what is unwanted
                return null
            }
            // TODO: replace by setDates from https://github.com/simonpoole/OpeningHoursParser/releases/tag/0.17.0 once available
            // it should appear on for example https://bintray.com/simonpoole/osm/OpeningHoursParser once uploaded
            returned.setMonthdays(simplifiedDateRanges)
        }
        if (rule.times == null) {
            // explicit opening hours are required by SC
            return null
        } else {
            val simplifiedTimespans: ArrayList<TimeSpan> = ArrayList()
            for (time in rule.times) {
                val simplifiedTimespan = reduceTimeRangeToSimpleTime(time) ?: return null
                simplifiedTimespans.add(simplifiedTimespan)
            }
            // multiple timespans may happen for rules such as "Mo-Su 09:00-12:00, 13:00-14:00"
            returned.times = simplifiedTimespans
        }
        if (rule.modifier != null) {
            val modifier = reduceModifierToAcceptedBySC(rule.modifier) ?: return null
            returned.modifier = modifier
        }
        if (rule.holidays != null) {
            val holidays = reduceHolidaysToAcceptedBySC(rule.holidays) ?: return null
            returned.holidays = holidays
        }
        return if (rule == returned) {
            // original rule is representable in SC UI without any loss
            returned
        } else {
            // not representable in SC UI
            null
        }
    }

    private fun reduceModifierToAcceptedBySC(modifier: RuleModifier): RuleModifier? {
        // public holidays with "off" specified explicitly are incompatible with SC due to
        // https://github.com/westnordost/StreetComplete/issues/276
        // other opening hours using "off" are rare and would require automated conversion
        // that would drop off part, what may cause issues in weird cases
        if (modifier.modifier != RuleModifier.Modifier.OPEN) {
            return null
        }
        return modifier
    }

    private fun reduceHolidaysToAcceptedBySC(holidays: List<Holiday>): List<Holiday>? {
        // PH, with set opening hours variant is supported by SC
        // many other variants are not, holidays list longer than 1 entry
        // indicates unsupported use
        if(holidays.size > 1) {
            return null
        }
        val holiday = holidays[0]
        val returned = Holiday()
        if(!holiday.useAsWeekDay) {
            // SC is not supporting "public holidays on Mondays" combinations
            return null
        }
        returned.useAsWeekDay = true
        if(holiday.type != Holiday.Type.PH) {
            // SC is not supporting SH
            return null
        }
        returned.type = Holiday.Type.PH
        return listOf(returned)
    }

    // StreetComplete is not supporting offsets, indexing by nth day of week etc
    // function may return identical or modified object or null
    // null or modified object indicates that original object was not representable in SC
    private fun reduceWeekDayRangeToSimpleDays(weekDayRange: WeekDayRange): WeekDayRange? {
        val returned = WeekDayRange()
        if(weekDayRange.startDay == null){
            // invalid range
            return null
        }
        // returned.endDay may be null for range containing just a single day
        returned.endDay = weekDayRange.endDay
        returned.startDay = weekDayRange.startDay
        return returned
    }

    // StreetComplete supports solely date changing based on month
    // without any support for any other data ranges
    // function may return identical or modified object or null
    // null or modified object indicates that original object was not representable in SC
    private fun reduceDateRangeToFullMonths(dateRange: DateRange): DateRange? {
        for (date in arrayOf(dateRange.startDate, dateRange.endDate).filterNotNull()) {
            if (date.isOpenEnded) {
                return null //TODO: it may be supported by StreetComplete
            }
            if (date.weekDayOffset != null) {
                return null
            }
            if (date.dayOffset != 0) {
                return null
            }
        }
        val newDateRange = DateRange()

        val startDate = DateWithOffset()
        startDate.month = dateRange.startDate.month
        newDateRange.startDate = startDate

        if(dateRange.endDate != null) {
            // range with just single month will have endDate unset
            val endDate = DateWithOffset()
            endDate.month = dateRange.endDate.month
            newDateRange.endDate = endDate
        }
        return newDateRange
    }

    // StreetComplete has no support for times like "from sunrise to sunset"
    // this function throws away any info over "from hour X to hour Y"
    // function may return identical or modified object or null
    // null or modified object indicates that original object was not representable in SC
    private fun reduceTimeRangeToSimpleTime(timeSpan: TimeSpan): TimeSpan? {
        val simplifiedTimespan = TimeSpan()
        if (timeSpan.startEvent != null) {
            return null
        }
        if (timeSpan.endEvent != null) {
            return null
        }
        val startInMinutesSinceMidnight = timeSpan.start
        if (startInMinutesSinceMidnight < 0) {
            return null
        }
        if (startInMinutesSinceMidnight > 24 * 60) {
            return null
        }
        simplifiedTimespan.start = startInMinutesSinceMidnight
        val endInMinutesSinceMidnight = timeSpan.end
        if (endInMinutesSinceMidnight < 0) {
            return null
        }
        simplifiedTimespan.end = endInMinutesSinceMidnight
        return simplifiedTimespan
    }

    private fun emptyRule(): Rule {
        // workaround needed to construct empty Rule object
        // proposal to allow creation of Rule objects is at
        // https://github.com/simonpoole/OpeningHoursParser/pull/24
        val input = ByteArrayInputStream("".toByteArray())
        val parser = OpeningHoursParser(input)
        try {
            val rules = parser.rules(true)
            return rules[0]
        } catch (e: ParseException) {
            e.printStackTrace()
            throw RuntimeException()
        }
    }

}