set finalDeckPath to "/Users/ryoto/code/java-learning/shohko_birthday_quiz_2026_timer_reset.pptx"
set oldTestPath to "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/powerpoint-test2.pptx"
set questionOneCapture to "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/final-qa/slideshow-question-1.png"
set questionTwoCapture to "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/final-qa/slideshow-question-2.png"

tell application "Microsoft PowerPoint"
	activate
	repeat with presentationIndex from (count of presentations) to 1 by -1
		set openPresentation to presentation presentationIndex
		set openPath to full name of openPresentation
		if openPath is oldTestPath or openPath is finalDeckPath then close openPresentation saving no
	end repeat
	open finalDeckPath
	set thePresentation to active presentation
	set showSettings to slide show settings of thePresentation
	set showWindow to run slide show showSettings
	set showView to slideshow view of showWindow
	go to slide showView number 7
	delay 5
	do shell script "/usr/sbin/screencapture -x " & quoted form of questionOneCapture
	go to next slide showView
	go to next slide showView
	delay 1
	do shell script "/usr/sbin/screencapture -x " & quoted form of questionTwoCapture
	set secondPosition to current show position of showView
	exit slide show showView
	close thePresentation saving no
	return "position=" & secondPosition
end tell
