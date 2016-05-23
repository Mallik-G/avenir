#!/Users/pranab/Tools/anaconda/bin/python

import sys
import random 
import time
import math

def randomFloat(low, high):
	return random.random() * (high-low) + low

def minLimit(val, min):
	if (val < min):
		val = min
	return val
	
def rangeLimit(val, min, max):
	if (val < min):
		val = min
	elif (val > max):
		val = max
	return val

# gaussian sampling based on rejection sampling	
class GaussianRejectSampler:
	def __init__(self, mean, stdDev):
		self.mean = mean
		self.stdDev = stdDev
		self.xmin = mean - 3 * stdDev
		self.xmax = mean + 3 * stdDev
		self.ymin = 0.0
		self.fmax = 1.0 / (math.sqrt(2.0 * 3.14) * stdDev)
		self.ymax = 1.05 * self.fmax
		
		
	def sample(self):
		done = False
		samp = 0
		while not done:
			x = randomFloat(self.xmin, self.xmax)
			y = randomFloat(self.ymin, self.ymax)
			f = self.fmax * math.exp(-(x - self.mean) * (x - self.mean) / (2.0 * self.stdDev * self.stdDev))
			if (y < f):
				done = True
				samp = x
		return samp

# histogram class
class Histogram:
	def __init__(self, min, binWidth, values):
		self.xmin = min
		self.xmax = min + binWidth * (len(values) - 1)
		self.ymin = 0
		self.binWidth = binWidth
		self.values = values
		self.fmax = 0
		for v in values:
			if (v > self.fmax):
				self.fmax = v
		self.ymin = 0.0
		self.ymax = self.fmax

	def value(self, x):
		bin = int((x - self.xmin) / self.binWidth)
		f = self.values[bin]
		return f
		
	def getMinMax(self):
		return (self.xmin, self.xmax)
		
	def boundedValue(self, x):
		if x < self.xmin:
			x = self.xmin
		elif x > self.xmax:
			x = self.xmax
		return x

# non parametric sampling using given distribution based on rejection sampling	
class NonParamRejectSampler:
	def __init__(self, min, binWidth, *values):
		self.xmin = min
		self.xmax = min + binWidth * (len(values) - 1)
		self.ymin = 0
		self.binWidth = binWidth
		self.values = values
		self.fmax = 0
		for v in values:
			if (v > self.fmax):
				self.fmax = v
		self.ymin = 0.0
		self.ymax = self.fmax
	
	def sample(self):
		done = False
		samp = 0
		while not done:
			x = random.randint(self.xmin, self.xmax)
			y = random.randint(self.ymin, self.ymax)
			bin = (x - self.xmin) / self.binWidth
			f = values[bin]
			if (y < f):
				done = True
				samp = x
		return samp

# metropolitan sampler		
class MetropolitanSampler:
	def __init__(self, propStdDev, min, binWidth, *values):
		self.targetDistr = Histogram(min, binWidth, values)
		self.propsalDistr = GaussianRejectSampler(0, propStdDev)
		
		# bootstrap sample
		(min, max) = self.targetDistr.getMinMax()
		self.curSample = random.randint(min, max)
		self.curDistr = self.targetDistr.value(self.curSample)
		self.transCount = 0
		
	def sample(self):
		nextSample = self.curSample + self.propsalDistr.sample()
		nextSample = self.targetDistr.boundedValue(nextSample)
		nextDistr = self.targetDistr.value(nextSample)
			
		transition = False
		if nextDistr > self.curDistr:
			transition = True
		else:
			distrRatio = float(nextDistr) / self.curDistr
			if distrRatio < random.random():
				transition = True
					
		if transition:
			self.curSample = nextSample
			self.curDistr = nextDistr
			self.transCount += 1
			
		return self.curSample;




