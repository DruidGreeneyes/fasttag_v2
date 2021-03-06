// Copyright 2003-2008.  Mark Watson (markw@markwatson.com).  All rights reserved.
// This software is released under the LGPL (www.fsf.org)
// For an alternative non-GPL license: contact the author
// THIS SOFTWARE COMES WITH NO WARRANTY

package com.ooba.nlp.fasttag;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import com.ooba.nlp.util.Tokenizer;
import com.ooba.nlp.util.Util;

import pair.Pair;

/**
 * <p/>
 * Copyright 2002-2007 by Mark Watson. All rights reserved.
 * <p/>
 */
public final class FastTag {

    private static final Map<String, String[]> lexicon = buildLexicon();

    /**
     *
     * @param word
     * @return true if the input word is in the lexicon, otherwise return false
     */
    public static boolean wordInLexicon(final Map<String, String[]> lexicon,
            final String word) {
        return lexicon.containsKey(word)
                || lexicon.containsKey(word.toLowerCase());
    }

    public static String[] getWordFromLexicon(
            final Map<String, String[]> lexicon, final String word) {
        return lexicon.getOrDefault(word, lexicon.get(word.toLowerCase()));
    }

    // The following is a list of rules through which we run the sequence of
    // words.

    // rule 0: if it exists in the lexicon, assign the first provided tag.
    // otherwise, if it is length 1, call it unknown, if not, call it a noun.
    private static Function<Pair<String, String[]>, Pair<String, String>> rule_0 = p -> {
        final String[] ss = p.right;
        String res;
        if (ss == null)
            if (p.left.length() == 1)
                res = "^";
            else
                res = "NN";
        else
            res = ss[0];
        return p.mapRight(res);
    };

    // rule 1: DT, {VBD | VBP} --> DT, NN
    private static UnaryOperator<Pair<String, String>> rule_1() {
        final ArrayBlockingQueue<String> window = new ArrayBlockingQueue<>(2);
        final String[] verbs = { "VBD", "VBP", "VB" };
        return p -> {
            window.add(p.right);
            return Util.onlyIf(
                    pair -> (window.size() == 2 && window.remove().equals("DT")
                            && Util.arrayContains(verbs, pair.right)),
                    Pair.F.replaceRight("NN"), p);
        };
    }

    // rule 2: convert a noun to a number (CD) if "." appears in the word
    private static UnaryOperator<Pair<String, String>> rule_2 = Util.onlyIf(
            pair -> (pair.right.startsWith("N") && (pair.left.contains(".")
                    || Util.containsFloat(pair.left))),
            Pair.F.replaceRight("CD"));

    // rule 3: convert a noun to a past participle if words.get(i) ends with
    // "ed"
    private static UnaryOperator<Pair<String, String>> rule_3 = Util.onlyIf(
            pair -> (pair.right.startsWith("N") && pair.left.endsWith("ed")),
            Pair.F.replaceRight("VBN"));

    // rule 4: convert any type to adverb if it ends in "ly";
    private static UnaryOperator<Pair<String, String>> rule_4 = Util.onlyIf(
            pair -> pair.left.endsWith("ly"), Pair.F.replaceRight("RB"));

    // rule 5: convert a common noun (NN or NNS) to a adjective if it ends with
    // "al"
    private static UnaryOperator<Pair<String, String>> rule_5 = Util.onlyIf(
            pair -> (pair.right.startsWith("NN") && pair.left.endsWith("al")),
            Pair.F.replaceRight("JJ"));

    // rule 6: convert a noun to a verb if the preceeding work is "would"
    private static UnaryOperator<Pair<String, String>> rule_6() {
        final ArrayBlockingQueue<String> window = new ArrayBlockingQueue<>(2);
        return p -> {
            window.add(p.left);
            return Util.onlyIf(
                    pair -> (window.size() == 2 && p.right.startsWith("NN")
                            && window.remove().equalsIgnoreCase("would")),
                    Pair.F.replaceRight("VB"), p);
        };
    }

    // rule 7: if a word has been categorized as a common noun and it ends with
    // "s",
    // then set its type to plural common noun (NNS)
    private static UnaryOperator<Pair<String, String>> rule_7 = Util.onlyIf(
            pair -> (pair.right.equals("NN") && pair.left.endsWith("s")),
            Pair.F.replaceRight("NNS"));

    // rule 8: convert a common noun to a present participle verb (i.e., a
    // gerand)
    private static UnaryOperator<Pair<String, String>> rule_8 = Util.onlyIf(
            pair -> (pair.right.equals("NN") && pair.left.endsWith("ing")),
            Pair.F.replaceRight("VBG"));

    /**
     *
     * @param words
     *            list of strings to tag with parts of speech
     * @return list of strings for part of speech tokens
     */
    public static List<String> tag(final Map<String, String[]> lexicon,
            final List<String> words) {
        return words.stream().sequential().map(FastTag.tag(lexicon)).collect(
                Collectors.toList());
    }

    /**
     * Same as tag for List[String]s, but only operates on a single word, so
     * that you can map it across a string[] or something if you want. MAKE SURE
     * THIS RUNS SEQUENTIALLY, as it needs to preserve some information about
     * its current context in the sentence, and that breaks if it runs in
     * parallel.
     *
     * @param lexicon
     * @param word
     * @return the word, tagged: word/tag
     */
    public static String tag(final Map<String, String[]> lexicon,
            final String word) {
        final UnaryOperator<Pair<String, String>> rule_1 = rule_1();
        final UnaryOperator<Pair<String, String>> rule_6 = rule_6();
        final Pair<String, String[]> pp = Pair.make(word,
                getWordFromLexicon(lexicon, word));
        Pair<String, String> p;
        p = rule_0.apply(pp);
        p = rule_1.apply(p);
        p = rule_2.apply(p);
        p = rule_3.apply(p);
        p = rule_4.apply(p);
        p = rule_5.apply(p);
        p = rule_6.apply(p);
        p = rule_7.apply(p);
        p = rule_8.apply(p);
        return p.intoFun((a, b) -> a + "/" + b);
    }

    public static UnaryOperator<String> tag(
            final Map<String, String[]> lexicon) {
        return w -> tag(lexicon, w);
    }

    public static Map<String, String[]> buildLexicon() {
        return buildLexicon("lexicon.txt");
    }

    public static Pair<String, String[]> getTagsFromLine(final String line) {
        final String[] bits = line.split(" ");
        return Pair.make(bits[0], Util.subarray(bits, 1));
    }

    public static Map<String, String[]> buildLexicon(final String path) {
        final Map<String, String[]> lexicon = new HashMap<>();
        try {
            Files.lines(Paths.get(path))
                 .filter(s -> s.contains(" "))
                 .map(FastTag::getTagsFromLine)
                 .forEach(Pair.F.intoCon(lexicon::put));
        } catch (final IOException e) {
            e.printStackTrace();
        }
        return lexicon;
    }

    /*********************************
     * Old Code from here on out.
     ********************************/
    @Deprecated
    public static List<String> _tag(final Map<String, String[]> lexicon,
            final List<String> words) {
        // Stream<String> res = words.stream().sequential().map(w ->
        // Pair.make(w, getWordFromLexicon(lexicon,
        // w))).map(rule_0).map(rule_1(2)).map(rule_2)

        final List<String> ret = new ArrayList<>(words.size());
        for (int i = 0, size = words.size(); i < size; i++) {
            String[] ss = lexicon.get(words.get(i));
            // 1/22/2002 mod (from Lisp code): if not in hash, try lower case:
            if (ss == null)
                ss = lexicon.get(words.get(i).toLowerCase());
            if (ss == null && words.get(i).length() == 1)
                ret.add(words.get(i) + "^");
            else if (ss == null)
                ret.add("NN");
            else
                ret.add(ss[0]);
        }
        /**
         * Apply transformational rules
         **/
        for (int i = 0; i < words.size(); i++) {
            final String word = ret.get(i);
            // rule 1: DT, {VBD | VBP} --> DT, NN
            if (i > 0 && ret.get(i - 1).equals("DT"))
                if (word.equals("VBD") || word.equals("VBP")
                        || word.equals("VB"))
                    ret.set(i, "NN");
            // rule 2: convert a noun to a number (CD) if "." appears in the
            // word
            if (word.startsWith("N")) {
                if (words.get(i).indexOf(".") > -1)
                    ret.set(i, "CD");
                try {
                    Float.parseFloat(words.get(i));
                    ret.set(i, "CD");
                } catch (final Exception e) { // ignore: exception OK: this just
                                              // means
                    // that the string could not parse as a
                    // number
                }
            }
            // rule 3: convert a noun to a past participle if words.get(i) ends
            // with "ed"
            if (ret.get(i).startsWith("N") && words.get(i).endsWith("ed"))
                ret.set(i, "VBN");
            // rule 4: convert any type to adverb if it ends in "ly";
            if (words.get(i).endsWith("ly"))
                ret.set(i, "RB");
            // rule 5: convert a common noun (NN or NNS) to a adjective if it
            // ends with "al"
            if (ret.get(i).startsWith("NN") && words.get(i).endsWith("al"))
                ret.set(i, "JJ");
            // rule 6: convert a noun to a verb if the preceeding work is
            // "would"
            if (i > 0 && ret.get(i).startsWith("NN")
                    && words.get(i - 1).equalsIgnoreCase("would"))
                ret.set(i, "VB");
            // rule 7: if a word has been categorized as a common noun and it
            // ends with "s",
            // then set its type to plural common noun (NNS)
            if (ret.get(i).equals("NN") && words.get(i).endsWith("s"))
                ret.set(i, "NNS");
            // rule 8: convert a common noun to a present participle verb (i.e.,
            // a gerand)
            if (ret.get(i).startsWith("NN") && words.get(i).endsWith("ing"))
                ret.set(i, "VBG");
        }
        return ret;
    }

    /**
     * Simple main test program
     *
     * @param args
     *            string to tokenize and tag
     */
    public static void main(final String[] args) {
        String text;
        if (args.length == 0) {
            System.out.println(
                    "Usage: argument is a string like \"The ball rolled down the street.\"\n\nSample run:\n");
            text = "The ball rolled down the street.";
        } else
            text = args[0];

        final List<String> words = Tokenizer.wordsToList(text);
        final List<String> tags = _tag(lexicon, words);
        for (int i = 0; i < words.size(); i++)
            System.out.println(words.get(i) + "/" + tags.get(i));
    }

    @Deprecated
    public static Map<String, String[]> _buildLexicon(final String path) {
        final Map<String, String[]> lexicon = new HashMap<>();
        try (Scanner scanner = new Scanner(
                new FileInputStream(path)).useDelimiter(
                        System.getProperty("line.separator"))) {
            while (scanner.hasNext()) {
                final String line = scanner.next();
                int count = 0;
                for (int i = 0, size = line.length(); i < size; i++)
                    if (line.charAt(i) == ' ')
                        count++;
                if (count == 0)
                    continue;
                try (Scanner lineScanner = new Scanner(line);
                        Scanner ls = lineScanner.useDelimiter(" ")) {
                    final String[] ss = new String[count];
                    final String word = ls.next();
                    count = 0;
                    while (ls.hasNext())
                        ss[count++] = ls.next();
                    lexicon.put(word, ss);
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
        return Collections.unmodifiableMap(lexicon);
    }

}
