set deckPath to "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/powerpoint-test2.pptx"
set gifPath to "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/source-unpacked/ppt/media/image2.gif"

tell application "Microsoft PowerPoint"
	activate
	repeat with presentationIndex from (count of presentations) to 1 by -1
		set openPresentation to presentation presentationIndex
		if (full name of openPresentation) is deckPath then close openPresentation saving no
	end repeat
	open deckPath
	set thePresentation to active presentation
	try
		set targetSlide to slide 7 of thePresentation
		set targetShapeIndex to 0
		repeat with shapeIndex from 1 to count of shapes of targetSlide
			set candidateShape to shape shapeIndex of targetSlide
			set candidateName to name of candidateShape
			if candidateName starts with "Google Shape;128;" then
				set targetShapeIndex to shapeIndex
				exit repeat
			end if
		end repeat
		if targetShapeIndex is 0 then error "Timer shape was not found on slide 7"
		set targetShape to shape targetShapeIndex of targetSlide
		set savedName to name of targetShape
		set savedLeft to left position of targetShape
		set savedTop to top of targetShape
		set savedWidth to width of targetShape
		set savedHeight to height of targetShape
		delete targetShape
		set replacementPicture to make new picture at end of targetSlide with properties {file name:gifPath}
		set name of replacementPicture to savedName
		set left position of replacementPicture to savedLeft
		set top of replacementPicture to savedTop
		set width of replacementPicture to savedWidth
		set height of replacementPicture to savedHeight
		save thePresentation
		close thePresentation
		return savedName & "|" & savedLeft & "|" & savedTop & "|" & savedWidth & "|" & savedHeight
	on error errorMessage number errorNumber
		close thePresentation saving no
		error errorMessage number errorNumber
	end try
end tell
